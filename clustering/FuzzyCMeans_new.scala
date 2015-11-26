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

import org.apache.spark.Logging
// import org.apache.spark.mllib.clustering.KMeansModel
import org.apache.spark.mllib.linalg.{Vectors, Vector}
import org.apache.spark.rdd.RDD
import org.apache.spark.storage.StorageLevel


class FuzzyCKMeans private (
                       private var clustersNum: Int,
                       private var maxIterations: Int,
                       // private var runs: Int,
                       // private var initializationMode: String,
                       // private var initializationSteps: Int,
                       private var epsilon: Double,
                       private var fuzzynessCoefficient: Double )
                       // private var seed: Long)
                          extends Serializable with Logging {

  // def this() = this(2, 20, 1, KMeans.K_MEANS_PARALLEL, 5, 1e-4, Utils.random.nextLong())
  def this() = this(2, 20, 1, 1e-4)

  def getClustersNum: Int = this.clustersNum

  def setClustersNum(clustersNum: Int): this.type = {
    if (clustersNum <= 0) {
      throw new IllegalArgumentException("Number of clusters must be positive")
    }
    this.clustersNum = clustersNum
    this
  }

  def getMaxIterations: Int = this.maxIterations

  def setMaxIterations(maxIter : Int): this.type = {
    if (maxIter <= 0) {
      throw new IllegalArgumentException("Number of max iterations must be positive")
    }
    this.maxIterations = maxIter
    this
  }

  def getEpsilon: Double = this.epsilon

  def setEplison(epsilon: Double): this.type = {
    if (epsilon < 0 || epsilon > 1) {
      throw new IllegalArgumentException("Epsilon value must be between 0 and 1")
    }
    this.epsilon = epsilon
    this
  }

  def getFuzzynessCoefficient: Double = this.fuzzynessCoefficient

  def setFuzzynessCoefficient(coeficient: Double): this.type = {
    if (coeficient < 0) {
      throw new IllegalArgumentException("Fuzzyness coefficient must be bigger than 1")
    }
    this.fuzzynessCoefficient = coeficient
    this
  }

  def run(data: RDD[Vector]): FuzzyCMeansModel = {

    if (data.getStorageLevel == StorageLevel.NONE) {
      logWarning("The input data is not directly cached, which may hurt performance if its"
        + " parent RDDs are also uncached.")
    }

    // Compute squared norms and cache them.
    val norms = data.map(Vectors.norm(_, 2.0))
    norms.persist()
    val zippedData = data.zip(norms).map { case (v, norm) =>
      new VectorWithNorm(v, norm)
    }
    val model = runAlgorithm(zippedData)
    norms.unpersist()

    // Warn at the end of the run as well, for increased visibility.
    if (data.getStorageLevel == StorageLevel.NONE) {
      logWarning("The input data was not directly cached, which may hurt performance if its"
        + " parent RDDs are also uncached.")
    }
    model
  }

}

