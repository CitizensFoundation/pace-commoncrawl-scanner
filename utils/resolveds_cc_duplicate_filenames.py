duplicateFile = open('duplicates', 'r')
DupLines = duplicateFile.readlines()
print(len(DupLines))

originalDownloadFile = open('downloadList', 'r')
OriginalDownloadLines = originalDownloadFile.readlines()
print(len(OriginalDownloadLines))

newDownloadList = []

def add_matching_from_original(filename):
    for line in OriginalDownloadLines:
        if filename in line:
            if line not in newDownloadList:
                newDownloadList.append(line)

for line in DupLines:
    filename = line.split('/')[-1]
    add_matching_from_original(filename)
    print(len(newDownloadList))

# writing to file
outFile = open('newDownloadlist', 'w')
outFile.writelines(newDownloadList)
outFile.close()
