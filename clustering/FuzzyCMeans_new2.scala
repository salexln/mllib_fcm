/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.mllib.clustering

//import org.apache.spark.mllib.clustering.{KMeans, KMeansModel}

//class FuzzyCMeans_2 private (
//    private var c: Int,
//    private var maxIterations: Int,
//    private var runs: Int,
//    private var initializationMode: String,
//    private var initializationSteps: Int,
//    private var epsilon: Double,
//    private var seed: Long,
//    private var fuzzynessCoefficient: Double)
//  extends KMeans
//extends KMeans(c, maxIterations, runs, initializationMode, initializationSteps, epsilon, seed)
//  {


//  def this() = this(2, 20, 1, KMeans.K_MEANS_PARALLEL, 5, 1e-4, Utils.random.nextLong(), 1e-4)

//  override private def runAlgorithm(data: RDD[VectorWithNorm]): KMeansModel = {

//  }

// }

class FuzzyCMeans_new2() {
    var a = KMeans.K_MEANS_PARALLEL

}


//class FuzzyCMeans_new2 (
//                       private var k: Int) {
//
//                       }