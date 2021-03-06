package com.milaboratory.migec

/**
 Copyright 2014 Mikhail Shugay (mikhail.shugay@gmail.com)

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 */

def DEFAULT_THRESHOLD = "10"

def cli = new CliBuilder(usage:
        'FilterReadsByCount [options] input.fastq[.gz] output.fastq[.gz]\n[for benchmarking purposes]')
cli.t(args: 1, "Count threshold. $DEFAULT_THRESHOLD")
cli.g("Grouped output: reads collapsed, quality average, count in header.")

def scriptName = getClass().canonicalName

def opt = cli.parse(args)
if (opt == null || opt.arguments().size() < 2) {
    println "[ERROR] Too few arguments provided"
    cli.usage()
    System.exit(-1)
}

int threshold = (opt.t ?: DEFAULT_THRESHOLD).toInteger()
boolean group = opt.g ? true : false
def inputFileName = opt.arguments()[0], outputFileName = opt.arguments()[1]

def reader = Util.getReader(inputFileName)

class ReadData {
    int count = 0
    final long[] qualArr

    ReadData(String seq) {
        this.qualArr = new long[seq.length()]
    }

    void append(String qual, boolean group) {
        count++

        if (group)
            for (int i = 0; i < qual.length(); i++)
                qualArr[i] += Util.qualFromSymbol(qual.charAt(i))
    }

    String finalizeQual() {
        char[] qual = new char[qualArr.length]

        for (int i = 0; i < qualArr.length; i++)
            qual[i] = Util.symbolFromQual((byte) (qualArr[i] / count))

        new String(qual)
    }
}

def readDataMap = new HashMap<String, ReadData>()

int nReads = 0

while (reader.readLine() != null) {
    def seq = reader.readLine()
    reader.readLine()
    def qual = reader.readLine()

    def readData = readDataMap[seq]

    if (readData == null)
        readDataMap.put(seq, readData = new ReadData(seq))

    readData.append(qual, group)

    if (++nReads % 500000 == 0)
        println "[${new Date()} $scriptName] Loaded $nReads reads"
}

nReads = 0
int nPassedReads = 0

def writer = Util.getWriter(outputFileName)

if (group) {
    int nReadGroups = 0
    readDataMap.each {
        int count = it.value.count
        if (count >= threshold) {
            writer.writeLine("@GroupedRead Count:$it.value.count\n" + it.key + "\n+\n" + it.value.finalizeQual())
            nPassedReads += count
        }

        nReads += it.value.count

        if (++nReadGroups % (500000 / threshold) == 0)
            println "[${new Date()} $scriptName] Scanned $nReads reads, $nPassedReads reads passed"
    }
} else {
    reader = Util.getReader(inputFileName)

    def header

    while ((header = reader.readLine()) != null) {
        def seq = reader.readLine()

        int count = readDataMap[seq].count

        if (count >= threshold) {
            reader.readLine()
            def qual = reader.readLine()
            writer.writeLine(header + "\n" + seq + "\n+\n" + qual)
            nPassedReads++
        } else {
            reader.readLine()
            reader.readLine()
        }

        if (++nReads % 500000 == 0)
            println "[${new Date()} $scriptName] Scanned $nReads reads, $nPassedReads reads passed"
    }
}

writer.close()