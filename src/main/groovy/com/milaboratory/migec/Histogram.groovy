/**
 Copyright 2013-2014 Mikhail Shugay (mikhail.shugay@gmail.com)

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

package com.milaboratory.migec

import groovyx.gpars.GParsPool

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicIntegerArray

def cli = new CliBuilder(usage: 'Histogram [options] checkout_dir/ output_dir/')
cli.q(args: 1, argName: 'read quality (phred)', "barcode region quality threshold. " +
        "Default: $Util.DEFAULT_UMI_QUAL_THRESHOLD")
cli.p(args: 1, 'number of threads to use')
def opt = cli.parse(args)
if (opt == null || opt.arguments().size() < 2) {
    println "[ERROR] Too few arguments provided"
    cli.usage()
    System.exit(-1)
}
int THREADS = opt.p ? Integer.parseInt(opt.p) : Runtime.getRuntime().availableProcessors()
byte umiQualThreshold = opt.q ? Byte.parseByte(opt.q) : Util.DEFAULT_UMI_QUAL_THRESHOLD
def inputDir = opt.arguments()[0]

double percLowOverseq = 0.25, percHighOverseq = 0.10
int overseqPeakLow = 4, // 16
    overseqPeakHigh = 7 // 256

def scriptName = getClass().canonicalName
int nBins = 17

def scale = { Integer value ->
    (int) (Math.min(Math.log((double) value) / Math.log(2.0), nBins - 1))
}

def samples = new File("$inputDir/checkout.filelist.txt").readLines().findAll { !it.startsWith("#") }.collect {
    it.split("\t")
}

def outputDir = opt.arguments()[1]
new File(outputDir).mkdirs()

def bins = (0..<nBins).collect { (int) Math.pow(2, it) }

def BASE_HEADER = "#SAMPLE_ID\tSAMPLE_TYPE",
    HEADER = BASE_HEADER + "\t" + bins.join("\t"),
    ESTIMATES_HEADER = BASE_HEADER + "\t" +
            "TOTAL_READS\tTOTAL_MIGS\t" +
            "OVERSEQ_THRESHOLD\tCOLLISION_THRESHOLD\t" +
            "UMI_QUAL_THRESHOLD\tUMI_LEN"

def maxUmiSz = -1
def umiPwmUnitsTotal = new AtomicIntegerArray(4000), umiPwmReadsTotal = new AtomicIntegerArray(4000), // todo: consider including guava and long array, possible overflow
    umiGoodUnitsTotal = new AtomicInteger(), umiGoodReadsTotal = new AtomicInteger()

new File("$outputDir/overseq.txt").withPrintWriter { oWriter ->
    oWriter.println(HEADER)

    new File("$outputDir/overseq-units.txt").withPrintWriter { ouWriter ->
        ouWriter.println(HEADER)

        new File("$outputDir/collision1.txt").withPrintWriter { cWriter ->
            cWriter.println(HEADER)

            new File("$outputDir/collision1-units.txt").withPrintWriter { cuWriter ->
                cuWriter.println(HEADER)


                new File("$outputDir/estimates.txt").withPrintWriter { eWriter ->
                    eWriter.println(ESTIMATES_HEADER)

                    new File("$outputDir/pwm-units.txt").withPrintWriter { pwPwmUnits ->

                        new File("$outputDir/pwm.txt").withPrintWriter { pwPwm ->

                            samples.each { sampleEntry ->
                                println "[${new Date()} $scriptName] Processing ${sampleEntry[0]} (${sampleEntry[1]})"

                                def umiPwmUnits = new AtomicIntegerArray(4000), umiPwmReads = new AtomicIntegerArray(4000),
                                    umiGoodUnits = new AtomicInteger(), umiGoodReads = new AtomicInteger()
                                int umiSz = -1

                                // Accumulate UMIs
                                def umiCountMap = new HashMap<String, Integer>()
                                def reader = Util.getReader(sampleEntry[2])

                                String header

                                int nReads = 0
                                while ((header = reader.readLine()) != null) {
                                    def umi = Util.getUmi(header, umiQualThreshold)

                                    if (umi != null) {
                                        umiCountMap.put(umi, (umiCountMap.get(umi) ?: 0) + 1)

                                        if (umiSz < 0)
                                            umiSz = umi.length()
                                        else if (umiSz != umi.length()) {
                                            println "ERROR UMIs of various sizes are not supported in the same sample"
                                            System.exit(-1)
                                        }
                                    }

                                    reader.readLine()
                                    reader.readLine()
                                    reader.readLine()

                                    if (++nReads % 1000000 == 0)
                                        println "[${new Date()} $scriptName] Processed $nReads, ${umiCountMap.size()} UMIs so far"
                                }

                                maxUmiSz = Math.max(maxUmiSz, umiSz)

                                println "[${new Date()} $scriptName] Processed $nReads, ${umiCountMap.size()} UMIs total"

                                def overseqHist = new AtomicIntegerArray(nBins), overseqHistUnits = new AtomicIntegerArray(nBins),
                                    collisionHist = new AtomicIntegerArray(nBins), collisionHistUnits = new AtomicIntegerArray(nBins)

                                def nUmis = new AtomicInteger()

                                GParsPool.withPool THREADS, {
                                    umiCountMap.eachParallel { Map.Entry<String, Integer> umiEntry ->
                                        int thisCount = umiEntry.value, bin = scale(thisCount)

                                        // Append to cumulative overseq
                                        overseqHist.addAndGet(bin, thisCount)
                                        overseqHistUnits.incrementAndGet(bin)

                                        // Calculate 1-mm collisions
                                        char[] umi = umiEntry.key.toCharArray()
                                        boolean good = true

                                        loops:
                                        for (int i = 0; i < umi.length; i++) {
                                            char prevChar = umi[i]
                                            for (int j = 0; j < 4; j++) {
                                                char nt = Util.NTS[j]
                                                if (prevChar != nt) {
                                                    umi[i] = nt
                                                    def otherCount = umiCountMap.get(new String(umi))
                                                    if (otherCount != null && thisCount < otherCount) {
                                                        collisionHist.addAndGet(bin, thisCount)
                                                        collisionHistUnits.incrementAndGet(bin)
                                                        good = false
                                                        break loops // let's not over-count collisions
                                                    }
                                                }
                                            }
                                            umi[i] = prevChar
                                        }

                                        if (good) {
                                            umiGoodReads.addAndGet(thisCount)
                                            umiGoodUnits.incrementAndGet()
                                            umiGoodReadsTotal.addAndGet(thisCount)
                                            umiGoodUnitsTotal.incrementAndGet()
                                            for (int i = 0; i < umi.length; i++) {
                                                int x = 4 * i + Util.nt2code(umi[i])
                                                umiPwmUnits.incrementAndGet(x)
                                                umiPwmReads.addAndGet(x, thisCount)
                                                umiPwmUnitsTotal.incrementAndGet(x)
                                                umiPwmReadsTotal.addAndGet(x, thisCount)
                                            }
                                        }

                                        int nUmisCurrent = nUmis.incrementAndGet()

                                        if (nUmisCurrent % 10000 == 0)
                                            println "[${new Date()} $scriptName] Collecting stats, $nUmisCurrent UMIs processed"
                                    }
                                }

                                def row = sampleEntry[0..1].join("\t")

                                int overseqPeak = (0..<nBins).max { overseqHist.get(it) }

                                int collThreshold = 0, overseqThreshold = 0,
                                    overseqThresholdEmp = (int) Math.pow(2.0, overseqPeak / 2.0)

                                //if (overseqPeak <= overseqPeakLow) {
                                // EMPIRICAL
                                overseqThreshold = overseqThresholdEmp
                                collThreshold = overseqThresholdEmp
                                //}
                                /* DEPRCATED
                                else {
                                    // by percentile
                                    double p = (overseqPeak <= overseqPeakHigh) ? percLowOverseq : percHighOverseq

                                    int cumulativeCollisionReads = collisionHist.get(0),
                                        cumulativeOverseqReads = overseqHist.get(0)

                                    for (int i = 1; i < nBins; i++) {
                                        cumulativeCollisionReads += collisionHist.get(i)

                                        if (cumulativeCollisionReads / (double) nReads >= 1 - p) {
                                            collThreshold = i - 1
                                            break
                                        }
                                    }
                                    collThreshold = (int) Math.pow(2.0, collThreshold)

                                    for (int i = 1; i < nBins; i++) {
                                        cumulativeOverseqReads += overseqHist.get(i)

                                        if (cumulativeOverseqReads / (double) nReads >= p) {
                                            overseqThreshold = i - 1
                                            break
                                        }
                                    }
                                    overseqThreshold = (int) Math.pow(2.0, overseqThreshold)
                                } */

                                oWriter.println(row + "\t" + Util.toString(overseqHist))
                                cWriter.println(row + "\t" + Util.toString(collisionHist))
                                ouWriter.println(row + "\t" + Util.toString(overseqHistUnits))
                                cuWriter.println(row + "\t" + Util.toString(collisionHistUnits))
                                eWriter.println(row + "\t" +
                                        nReads + "\t" + nUmis + "\t" +
                                        overseqThreshold + "\t" + collThreshold + "\t" +
                                        umiQualThreshold + "\t" + umiSz)


                                def PWM_HEADER = ESTIMATES_HEADER = BASE_HEADER + "\tNT\t" + (1..umiSz).join("\t")
                                pwPwmUnits.println(PWM_HEADER)
                                pwPwm.println(PWM_HEADER)
                                for (int i = 0; i < 4; i++) {
                                    pwPwmUnits.print(row + "\t" + Util.code2nt(i))
                                    pwPwm.print(row + "\t" + Util.code2nt(i))
                                    for (int j = 0; j < umiSz; j++) {
                                        int x = 4 * j + i
                                        pwPwmUnits.print("\t" + umiPwmUnits.get(x) / (double) umiGoodUnits.get())
                                        pwPwm.print("\t" + umiPwmReads.get(x) / (double) umiGoodReads.get())
                                    }
                                    pwPwmUnits.println()
                                    pwPwm.println()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

new File("$outputDir/pwm-summary-units.txt").withPrintWriter { pwPwmUnits ->

    new File("$outputDir/pwm-summary.txt").withPrintWriter { pwPwm ->
        def PWM_HEADER = ESTIMATES_HEADER = "#NT\t" + (1..maxUmiSz).join("\t")
        pwPwmUnits.println(PWM_HEADER)
        pwPwm.println(PWM_HEADER)
        for (int i = 0; i < 4; i++) {
            pwPwmUnits.print(Util.code2nt(i))
            pwPwm.print(Util.code2nt(i))
            for (int j = 0; j < maxUmiSz; j++) {
                int x = 4 * j + i
                pwPwmUnits.print("\t" + umiPwmUnitsTotal.get(x) / (double) umiGoodUnitsTotal.get())
                pwPwm.print("\t" + umiPwmReadsTotal.get(x) / (double) umiGoodReadsTotal.get())
            }
            pwPwmUnits.println()
            pwPwm.println()
        }
    }
}