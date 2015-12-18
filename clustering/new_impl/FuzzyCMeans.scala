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
import org.apache.spark.util.random.XORShiftRandom
import org.apache.spark.mllib.linalg.{Vectors, Vector}
import org.apache.spark.rdd.RDD
import org.apache.spark.storage.StorageLevel
import breeze.linalg.{ DenseVector => BDV, Vector => BV}


/**
 *
 * @param clustersNum The wanted number of clusters
 * @param maxIterations Maximux iterations for the algorithm
 * @param epsilon Termination criterion
 * @param fuzzynessCoefficient Measures the tolerance of the required clustering.
 *                             This value determines how much the clusters can overlap with one
 *                             another. The higher the value of m, the larger the overlap
 *                             between clusters.
 */
class FuzzyCKMeans private ( private var clustersNum: Int,
                             private var maxIterations: Int,
                             // private var initializationMode: String,
                             private var epsilon: Double,
                             private var fuzzynessCoefficient: Double )
// private var seed: Long)
  extends Serializable with Logging {

  def this() = this(2, 20, 1e-4, 2)

  /**
   * Returns the number of clusters
   * @return Cluster number
   */
  def getClustersNum: Int = this.clustersNum

  /**
   * Sets number of the clusters
   * @param clustersNum Sets number of the wanted clusters
   * @return
   */
  def setClustersNum(clustersNum: Int): this.type = {
    if (clustersNum <= 0) {
      throw new IllegalArgumentException("Number of clusters must be positive")
    }
    this.clustersNum = clustersNum
    this
  }

  /**
   * Returns the number of the maximum iterations
   * @return Max iterations for the algorithm
   */
  def getMaxIterations: Int = this.maxIterations

  /**
   * Sets the max interations
   * @param maxIter set max number of iterations for the algorithm
   * @return
   */
  def setMaxIterations(maxIter : Int): this.type = {
    if (maxIter <= 0) {
      throw new IllegalArgumentException("Number of max iterations must be positive")
    }
    this.maxIterations = maxIter
    this
  }

  /**
   * Returns the termination criterion
   * @return Termination criterion
   */
  def getEpsilon: Double = this.epsilon

  /**
   * Sets the termination criterion
   * @param epsilon sets termination criterion for the algorithm
   * @return
   */
  def setEplison(epsilon: Double): this.type = {
    if (epsilon < 0 || epsilon > 1) {
      throw new IllegalArgumentException("Epsilon value must be between 0 and 1")
    }
    this.epsilon = epsilon
    this
  }

  /**
   * Returns the fuzzyness coefficient
   * @return Fuzzyness coefficient
   */
  def getFuzzynessCoefficient: Double = this.fuzzynessCoefficient

  /**
   * Sets the fuzzyness coefficient
   * @param coeficient Sets the fuzzyness coefficient
   * @return
   */
  def setFuzzynessCoefficient(coeficient: Double): this.type = {
    if (coeficient < 0) {
      throw new IllegalArgumentException("Fuzzyness coefficient must be bigger than 1")
    }
    this.fuzzynessCoefficient = coeficient
    this
  }

  /**
   * Train a Fuzzy C - Means model on the given set of points;
   * @param data Input data to the algorithm
   * @return FuzzyCMeansModel with the results of the run
   */
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

  /**
   * Implementation of the Fuzzy C - Means algorithm
   * @param data input data to the algorithm
   * @return FuzzyCMeansModel with the results of the run
   */
  private def runAlgorithm(data: RDD[VectorWithNorm]): FuzzyCMeansModel = {

    val sc = data.sparkContext
    val initStartTime = System.nanoTime()

    // random initializations of the centers
    val centers = initRandomCenters(data)

    val initTimeInSeconds = (System.nanoTime() - initStartTime) / 1e9
    logInfo(s"Initialization with took " + "%.3f".format(initTimeInSeconds) +
      " seconds.")

    val data_dim = data.first().vector.size

    var iteration = 0
    val iterationStartTime = System.nanoTime()
    var converged = false

    // Algorithm should stop if it is converged or it exceeded the max iterations
    while(iteration < maxIterations && !converged) {

      // broadcast the centers to all the machines
      val broadcasted_centers = sc.broadcast(centers)

      /**
      * Recalculate the centroid of each cluster using:
      *             FOR j = 0: j < clustersNum:
      *                 c_j = (SUM_i (u_i_j * x_i) ) / (SUM_i(u_i_j))
      */
      val totContr = data.mapPartitions { data_ponts =>
//        /**
//        * An array thet represemts the distance the data_point (x_i) from from each cluster
//        * cluster_to_point_distance[j] = ||x_i - c_j ||
//        */

        /**
        * An array thet represents the distance the data_point (x_i) from from each cluster
        * actual_cluster_to_point_distance [j] = (||x_i - c_j ||) ^^ (2/(m-1))
        */
//        val actual_cluster_to_point_distance = Array.fill[Double](clustersNum)(0)
        val actual_cluster_to_point_distance = Array.fill(clustersNum)(BDV.zeros[Double](data_dim )
                                                                      .asInstanceOf[BV[Double]])
//        val actual_cluster_to_point_distance = Array.fill[VectorWithNorm](clustersNum)()

//        val partial_num = Array.fill[Double](clustersNum)(0)
        val partialDen = Array.fill[Double](clustersNum)(0)
        val numDist = Array.fill[Double](clustersNum)(0)

        data_ponts.foreach { data_point =>
          /**
          * total_distance represents for each data_point the total distance from clusters:
          *
          *      total_distance = SUM_j 1 / ( (||data_point - c_j||)^^(2/ (m-1) ) )
          */
          var total_distance = 0.0

          // computation of the distance of data_point from each cluster:
          for (j <- 0 until clustersNum) {
            // the distance of data_point from cluster j:

            // Alex: is this corrent???

            val cluster_to_point_distance =
                        KMeans.fastSquaredDistance(broadcasted_centers.value(j), data_point)
            numDist(j) = math.pow(cluster_to_point_distance, 2/( fuzzynessCoefficient - 1))

            // update the total_distance:
            total_distance += (1 / numDist(j))
          }

          // calculation of the new values of the membership matrix:
          for (j <- 0 until clustersNum) {

            /**
            * u_i_j = 1 / ( SUM_k( (||x_i - c_j|| / ||x_i - c_K||) ^ (x/(m - 1))) )
            * this is the calculation of (u_ij)^m:
            */

            val u_i_j_m: Double = math.pow(numDist(j) * total_distance, -fuzzynessCoefficient)

            var dense_vec1: BDV[Double] = new BDV(data_point.vector.toArray)
            dense_vec1 *= u_i_j_m
            actual_cluster_to_point_distance(j) += dense_vec1 // local num of c(j) formula
            partialDen(j) += u_i_j_m // local den of c(j) formula
          }
        }


        val centerContribs = for (j <- 0 until clustersNum) yield {
          (j, (actual_cluster_to_point_distance(j), partialDen(j)))
        }
        centerContribs.iterator

      }.reduceByKey((x, y) => (x._1 + y._1, x._2 + y._2)).collectAsMap()



     // Update centers:
      var center_changed = false
      for (j <- 0 until clustersNum) {
        // create new center:

//        var new_center = new VectorWithNorm(Vectors.dense(v.vector.toArray), v.norm)
//        var new_center = new VectorWithNorm((Vectors.dense(totContr(j).toArray)))

        if (totContr(j)._2 != 0) {
          // create a new center:
          var dense_vec1: BDV[Double] = new BDV(totContr(j)._1.toArray)
          dense_vec1 /= totContr(j)._2

          val newCenter = new VectorWithNorm(dense_vec1.toArray)

          if (KMeans.fastSquaredDistance(newCenter, centers(j)) > epsilon * epsilon) {
            center_changed = true
          }
          centers(j) = newCenter
        }
      }


      if(!center_changed) {
        // this means that no change was made the we can stop
        converged = true
        logInfo("Run finished in " + (iteration + 1) + " iterations")
      } else {
        iteration += 1
      }

    }

    val iterationTimeInSeconds = (System.nanoTime() - iterationStartTime) / 1e9
    logInfo(s"Iterations took " + "%.3f".format(iterationTimeInSeconds) + " seconds.")

    if (iteration == maxIterations) {
      logInfo(s"Fuzzy C-Means reached the max number of iterations: $maxIterations.")
    } else {
      logInfo(s"Fuzzy C-Means converged in $iteration iterations.")
    }

    new FuzzyCMeansModel(centers.map(_.vector))
  }

  /**
   * Inits random centers
   * @param data input data
   * @return Array of random centers
   */
  private def initRandomCenters(data: RDD[VectorWithNorm])
  : Array[VectorWithNorm] = {
    // create a random seed
    val sample = data.takeSample(true, clustersNum, new XORShiftRandom().nextInt())
    sample
  }

}

/**
  * Top-level methods for calling K-means clustering.
  */
object FuzzyCMeans {

  /**
   * Trains a Fuzzy C - Means model using the given set of parameters.
   *
   * @param data training points stored as `RDD[Vector]`
   * @param clusterNum number of clusters
   * @param fuzzynessCoefficient measures the tolerance of the required clustering.
   *                             This value determines how much the clusters can overlap with one
   *                             another. The higher the value of m, the larger the overlap
   *                             between clusters.(must be greater than 1)
   * @param maxIterations max number of iterations
   * @param epsilon termination criterion (between 0 and 1)

   */
  def train(
      data: RDD[Vector],
      clusterNum: Int,
      fuzzynessCoefficient: Double,
      maxIterations: Int,
      epsilon: Double): FuzzyCMeansModel = {
    new FuzzyCKMeans().setClustersNum(clusterNum)
      .setFuzzynessCoefficient(fuzzynessCoefficient)
      .setMaxIterations(maxIterations)
      .setEplison(epsilon)
      .run(data)
  }

  def getFuzzynessCoefficient: Double = FuzzyCMeans.getFuzzynessCoefficient

}