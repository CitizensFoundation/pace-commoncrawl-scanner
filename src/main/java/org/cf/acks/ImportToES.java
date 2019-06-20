package org.cf.acks;

import com.amazonaws.transform.StaxUnmarshallerContext;
import com.fasterxml.jackson.core.io.JsonStringEncoder;
import com.gliwka.hyperscan.wrapper.Database;
import com.gliwka.hyperscan.wrapper.Match;
import com.gliwka.hyperscan.wrapper.Scanner;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.http.HttpHost;

import java.io.*;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Semaphore;
import java.util.zip.GZIPInputStream;

import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.update.UpdateRequest;
import org.elasticsearch.action.update.UpdateResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.client.indices.CreateIndexRequest;
import org.elasticsearch.client.indices.CreateIndexResponse;
import org.elasticsearch.client.indices.GetIndexRequest;
import org.elasticsearch.common.document.DocumentField;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.index.query.MatchQueryBuilder;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.search.builder.SearchSourceBuilder;

import net.openhft.hashing.LongHashFunction;

public class ImportToES implements Runnable {

    private static final Logger logger = LogManager.getLogger(ImportToES.class);

    public final int BUFFER_SIZE = 128_000;
    public final int ES_BULK_MAX_SIZE = 150;

    private final Semaphore schedulingSemaphore;
    private final String archive;
    private final String esHostname;
    private final Integer esPort;
    private final String esProtocol;
    private List<UpdateRequest> bulkUpdateQueue;

    //    private Integer esPort=9200;
//    private String esProtocol="http";
    final static String TARGET_URI_MARKER = "WARC-Target-URI:";
    final static String TARGET_DATE = "WARC-Date:";
    final static String CONTENT_LENGTH = "Content-Length";
    private RestHighLevelClient esClient;
    private final HashMap<Long, Long> pageRanks;

    ImportToES(Semaphore schedulingSemaphore, String archive, String esHostname, Integer esPort, String esProtocol, HashMap<Long, Long> pageRanks) {
        this.schedulingSemaphore = schedulingSemaphore;
        String[] archiveParts = archive.split("/");
        this.archive = "results/"+archiveParts[archiveParts.length-1];
        this.esHostname = esHostname;
        this.esPort = esPort;
        this.esProtocol = esProtocol;
        this.pageRanks = pageRanks;
        this.bulkUpdateQueue = new ArrayList<UpdateRequest>();
    }

    @Override
    public void run() {
        String currentURL = null; // Should never be null in practice.
        long startTime = System.currentTimeMillis();
        System.out.println("Importing: "+archive);
        File file = new File(archive);
        while (file.exists()==false) {
            try {
                Random rand = new Random();
                int n = rand.nextInt(2500);
                n += 3500;
                System.out.println("Waiting on file import: "+archive+" "+n/1000+"s");
                long retryDuration = System.currentTimeMillis() - startTime;
                if (retryDuration<5*60*1000) {
                    Thread.sleep(n);
                } else {
                    break;
                }
            } catch (Exception ex) {
                System.out.println("Error sleeping in thread: "+ex.getMessage());
            }
        }

        if (file.exists()) {
            if (!hasBeenImported(archive)) {
                BufferedReader contentReader = null;
                try {
                    final InputStream objectStream = new FileInputStream(new File(archive));
                    contentReader = new BufferedReader(new InputStreamReader(objectStream, StandardCharsets.UTF_8), BUFFER_SIZE);
                    this.esClient = new RestHighLevelClient(
                        RestClient.builder(
                                new HttpHost(this.esHostname, this.esPort, this.esProtocol)
                                ));

                    boolean processingEntry = false;

                    String line;
                    String currentDate = null;
                    int bulkCounter = 0;
                    outerloop: while ((line = contentReader.readLine()) != null) {
                        if (line.length()==0) {
                            processingEntry = true;
                            currentURL = null;
                            currentDate = null;
                        } else if (processingEntry && !line.contains("kd8x72dAx") && (line.startsWith("http://") || line.startsWith("https://"))) {
                            currentURL = line;
                            currentDate = contentReader.readLine();
                        } else if (processingEntry && currentURL != null && currentDate != null) {
                            try {
                                while ((line = contentReader.readLine()) != null && line.length()!=0) {
                                    if (line.startsWith("Duration")) {
                                        break outerloop;
                                    } else {
                                        importLinesToES(currentURL, line, currentDate);
                                        bulkCounter+=1;
                                        if (bulkCounter>ES_BULK_MAX_SIZE) {
                                            pumpBulkUpdateQueue();
                                            bulkCounter=0;
                                        }
                                    }
                                }
                            } catch (Throwable t) {
                                throw new IOException(t);
                            }
                            processingEntry = false;
                        }
                    }
                    try {
                        pumpBulkUpdateQueue();
                    } catch (Throwable t) {
                        throw new IOException(t);
                    }
                    contentReader.close();
                    esClient.close();
                    setState(archive, "completed");
                   /* if (file.delete())
                    {
                        System.out.println(archive+" deleted after ESImport");
                    }
                    else
                    {
                        System.out.println(archive+" FAILED! after ESImport");
                    }*/
                } catch (IOException io) {
                    logger.catching(io);
                    setState(archive, io.getMessage());
                } finally {
                    try {
                        contentReader.close();
                        esClient.close();
                    } catch (IOException io) {
                        logger.catching(io);
                    }
                    schedulingSemaphore.release();
                }
            } else {
                System.out.println("File already imported: "+archive);
                schedulingSemaphore.release();
            }
        } else {
            System.out.println("Timeout on file: "+archive);
            schedulingSemaphore.release();
        }
    }

    private boolean hasBeenImported(String archive) {
        String path = "state/"+getFilename(archive)+".importCompleted";
        File file = new File(path);
        return file.exists();
    }

    private void pumpBulkUpdateQueue() {
        if (this.bulkUpdateQueue.size()>0) {
            BulkRequest request = new BulkRequest();
            for(UpdateRequest update:this.bulkUpdateQueue){
                request.add(update);
            }
            try {
                esClient.bulk(request, RequestOptions.DEFAULT);
            } catch (IOException ioex) {
                try {
                    System.out.println("Sleeping because of IOException");
                    Thread.sleep(5*1000);
                    System.out.println("Retrying after IOException");
                    esClient.bulk(request, RequestOptions.DEFAULT);
                } catch (Exception ex) {
                    System.out.println("ERROR: pumpBulkUpdateQueue: "+ex.getMessage());
                }
            }
            this.bulkUpdateQueue.clear();
        }
    }

    private void setState(String archive, String status) {
        String outPath = "state/"+getFilename(archive)+".importCompleted";
        try {
            Writer stateWrite = new BufferedWriter(new FileWriter(new File(outPath)));
            stateWrite.write(status);
            stateWrite.close();
            }
        catch (IOException io) {
            logger.catching(io);
        }
    }

    private void importLinesToES(String url, String line, String currentDate) throws Throwable {
        String splitLines[] = line.split("kd8x72dAx");
        url = url.substring(0, Math.min(512, url.length()));
        URL uri = new URL(url);
        String domainName = uri.getHost();

        if (domainName!=null) {
            domainName = domainName.replace("www.","");
            Long domainHash = LongHashFunction.xx().hashChars(domainName);
            Long pageRank = this.pageRanks.get(domainHash);

            if (pageRank!=null) {
                if (splitLines.length>1) {
                    String paragraph = splitLines[0].replaceAll("\"","");
                    JsonStringEncoder e = JsonStringEncoder.getInstance();
                    paragraph = new String(e.quoteAsString(paragraph));
                    String strippedParagraph = paragraph.toLowerCase().replace(" ","").replace("'","").
                    replace("`","").
                    replace("´","").
                    replace("‘","").
                    replace("’","").
                    replace("”","").
                    replace(":","").
                    replace("?","").
                    replace(";","").
                    replace("“","");

                    Long pHashLong = LongHashFunction.xx().hashChars(strippedParagraph);
                    String pHash = Long.toString(pHashLong);
                    String urlIdHash = Long.toString(LongHashFunction.xx().hashChars(url+pHash));

                    String jsonString = "{\"createdAt\":\""+currentDate+"\",";
                    String keywords[] = splitLines[1].split(":");
                    Map<String,Integer> keyWordsmap = new HashMap<String,Integer>();

                    for(String keyword:keywords){
                        if (!keyWordsmap.containsKey(keyword)) {
                            keyWordsmap.put(keyword,1);
                        } else{
                            keyWordsmap.put(keyword, keyWordsmap.get(keyword)+1);
                        }
                    }

                    jsonString+="\"paragraph\":\""+paragraph+"\",\"keywords\":[";

                    int essentialKeywordsCount=0;
                    int additionalKeywordsCount=0;

                    for (Map.Entry<String, Integer> entry : keyWordsmap.entrySet()) {
                        String keyword = entry.getKey().replace("\\b", "");
                        String count = entry.getValue().toString();
                        boolean essential = false;
                        if (keyword.substring(keyword.length()-1).equals("E")) {
                            essential=true;
                            essentialKeywordsCount+=1;
                        } else {
                            additionalKeywordsCount+=1;
                        }
                        keyword = keyword.substring(0, keyword.length() - 1);

                        if (essential) {
                            jsonString += "{\"keyword\":\""+keyword+"\",\"count\":"+count+",\"essential\": true},";
                        } else {
                            jsonString += "{\"keyword\":\""+keyword+"\",\"count\":"+count+",\"essential\": false},";
                        }
                    }
                    jsonString = jsonString.substring(0, jsonString.length() - 1);

                    jsonString+="],";
                    jsonString+="\"essentialKwCount\":"+essentialKeywordsCount+",";
                    jsonString+="\"additionalKwCount\":"+additionalKeywordsCount+",";
                    jsonString+="\"uniqueKwCount\":"+keyWordsmap.entrySet().size()+",";
                    jsonString+="\"extRepostCount\": 0,";
                    jsonString+="\"intRepostCount\": 0,";
                    jsonString+="\"pHash\":"+pHash+",";

                    jsonString+="\"pageRank\":"+pageRank+",";
                    jsonString+="\"domainName\":\""+domainName+"\"";
                    jsonString+="}";

                    UpdateRequest esRequest = new UpdateRequest("urls", "doc", urlIdHash);
                    esRequest.doc(jsonString, XContentType.JSON);
                    esRequest.docAsUpsert(true);
                    this.bulkUpdateQueue.add(esRequest);
                } else {
                    throw new Exception("Splitlines! "+line);
                }
            } else {
            }
        } else {
        }
    }
    private String getFilename(String path) {
        String[] paths = path.split("/");
        return paths[paths.length-1];
    }
}