from simpletransformers.classification import ClassificationModel, ClassificationArgs
import pandas as pd
import logging
import torch
import random
import wandb
import os
import time
import shutil
import pathlib
import gc
from GPUtil import showUtilization as gpu_usage
from numba import cuda
import sys

from elasticsearch import Elasticsearch, helpers, exceptions

logging.basicConfig(level=logging.INFO)
transformers_logger = logging.getLogger("transformers")
transformers_logger.setLevel(logging.WARNING)

es_url = os.environ['AC_ANALYTICS_ES_URL'] if os.environ.get('AC_ANALYTICS_ES_URL')!=None else 'localhost:9200'
es = Elasticsearch(es_url)

class EsPredictions:
    exesModel = None
    onesModel = None
    predictionQueue = []
    esBulkUpdateQueue = []
    maxBulkUpdateQueueSize = 1000
    maxPredictionQueueSize = 2000
    totalProcessed = 0

    def updateRelevanceScore(self, id, score):
        doc = {
            "relevanceScore": score
        }

        self.esBulkUpdateQueue.append(
             {
                '_op_type': 'update',
                '_index': "urls",
                '_id': id,
                'doc': doc
        })
        #es.update(index="urls", id=id, body=update)
        print(f"Updated {id} relevance to {score}")

        if len(self.esBulkUpdateQueue)>=self.maxBulkUpdateQueueSize:
            self.pump_es_update_queue()

    def pump_es_update_queue(self):
        if len(self.esBulkUpdateQueue)>0:
            helpers.bulk(es, self.esBulkUpdateQueue)
            self.esBulkUpdateQueue = []

    def pump_prediction_queue(self):
        if len(self.predictionQueue)>0:
            ids = []
            paragraphs = []
            for predictFor in self.predictionQueue:
                ids.append(predictFor.get("id"))
                paragraphs.append(predictFor.get("paragraph"))

            exesPredictions, tmpA = self.exesModel.predict(paragraphs)
            onesPredictions, tmpB = self.onesModel.predict(paragraphs)

            if len(exesPredictions)!=len(paragraphs):
                raise Exception("len(exesPredictions)!=len(paragraphs)")

            for i in range(len(ids)):
                if exesPredictions[i]==0:
                    self.updateRelevanceScore(ids[i], 0)
                else:
                    if onesPredictions[i]==1:
                        self.updateRelevanceScore(ids[i], 1)
                    else:
                        self.updateRelevanceScore(ids[i], 3)

            self.predictionQueue = []

    def process_es_hit(self, hit):
        id = hit["_id"]
        source = hit["_source"]
        paragraph = source.get("paragraph").lower().strip()
        relevanceScore = source.get("relevanceScore")
        topic = source.get("topic")

        #print(f"Processing {id} - {topic}")

        self.predictionQueue.append({ "id": id, "paragraph": paragraph })

        if len(self.predictionQueue)>=self.maxPredictionQueueSize:
            self.pump_prediction_queue()

    def predict_all_for_topic(self, topic):
        print(f"Predicting for {topic}")

        #relevanceFilter = [{"relevanceScore": {"value": {"gte": 0 }}}]
        #onlyNotRatedFilter = [{"relevanceScore": {"value": -1 }}]
        query={
            "query": {
                "bool": {
                    "must": [
                        { "match": { "topic": topic } },
                        { "match": { "relevanceScore": -1 } }
                    ]
                 }
             }
        }


        hits = helpers.scan(es, index="urls", query=query, size=1000)

        for hit in hits:
            self.process_es_hit(hit)

        self.pump_prediction_queue()
        self.pump_es_update_queue()

    def predict_for_topics(self, topics):
        print("Loading exes model")
        self.exesModel = ClassificationModel(
             "bert", "models/binary",  use_cuda = True
        )

        print("Loading ones model")
        self.onesModel = ClassificationModel(
             "bert", "models/binaryOnes",  use_cuda = True
        )

        for topic in topics:
            self.predict_all_for_topic(topic)

topics1 = [
    "Left behind",
    "Family disintegration",
    "Loss of religion",
    "Evolving social mores",
    "Technology and alienation",
    "Losing cultural identity",
    "Income inequality",
    "Qanon"
    ]

topics2 = [
    "Desire for strong man",
    "Feeling ignored",
    "Distrust of media"
    "False accusations of racism",
    "Nanny state",
    "Call to vigilante action",
    "Dehumanization of opponents",
    "Restrictions on free speech",
    "Loss of sovereignty",
    "Undeserving support"
    ]

topics3 = [
    "Citizen Engagement",
    "Democratic Innovation"
    ]

topics4 = ["Resentment of elite"]

esPredictions = EsPredictions()

ex_type=Exception
limit=0
wait_ms=250
wait_increase_ratio=1
attempt = 0
option = sys.argv[1]

while True:
    try:
        if option=="1":
            esPredictions.predict_for_topics(topics1)
        elif option=="2":
            esPredictions.predict_for_topics(topics2)
        elif option=="3":
            esPredictions.predict_for_topics(topics3)
        elif option=="4":
            esPredictions.predict_for_topics(topics4)
        elif option=="5":
            esPredictions.predict_for_topics(topics5)
    except Exception as ex:
        if not isinstance(ex, ex_type):
            raise ex
        if 0 < limit <= attempt:
            print("no more attempts")
            raise ex

        print("failed execution attempt", attempt)

        attempt += 1
        time.sleep(wait_ms / 1000)
        wait_ms *= wait_increase_ratio



