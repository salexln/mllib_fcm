
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

//import org.apache.spark.mllib.linalg.{ Vector => Alex }
import org.apache.spark.Logging
import org.apache.spark.mllib.linalg.BLAS._
import org.apache.spark.util.random.XORShiftRandom

// import org.apache.spark.mllib.clustering.KMeansModel
import org.apache.spark.mllib.linalg.{DenseVector, Vectors, Vector}
import org.apache.spark.rdd.RDD
import org.apache.spark.storage.StorageLevel
import breeze.linalg.{ DenseVector => BDV }
import breeze.linalg.{ DenseVector => BDV, Vector => BV}
import scala.collection.mutable.ArrayBuffer



class FuzzyCKMeans private ( private var clustersNum: Int,
                             private var maxIterations: Int,
                             private var runs: Int,
                             // private var initializationMode: String,
                             // private var initializationSteps: Int,
                             private var epsilon: Double,
                             private var fuzzynessCoefficient: Double )
// private var seed: Long)
  extends Serializable with Logging {

  // def this() = this(2, 20, 1, KMeans.K_MEANS_PARALLEL, 5, 1e-4, Utils.random.nextLong())
  def this() = this(2, 20, 1, 1e-4, 2)

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

  def getRuns: Double = this.runs

  def setRuns(runs: Int): this.type = {
    this.runs = runs
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

  private def runAlgorithm(data: RDD[VectorWithNorm]): FuzzyCMeansModel = {

    val sc = data.sparkContext
    val initStartTime = System.nanoTime()

    val numRuns = runs

    /**
    * Randomly initialize C clusters
    */
    val centers = initRandomCenters(data)

    val initTimeInSeconds = (System.nanoTime() - initStartTime) / 1e9

    val dim = data.first().vector.size

    var iteration = 0
    val iterationStartTime = System.nanoTime()
    var converged = false

    // Implementation of Fuzzy C-Means algorithm:
    while(iteration < maxIterations && converged == false) {

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
        * An array thet represemts the distance the data_point (x_i) from from each cluster
        * actual_cluster_to_point_distance [j] = (||x_i - c_j ||) ^ (2/(m-1))
        */
//        val actual_cluster_to_point_distance = Array.fill[Double](clustersNum)(0)
        val actual_cluster_to_point_distance = Array.fill(clustersNum)(BDV.zeros[Double](dim)
                                                                      .asInstanceOf[BV[Double]])
//        val actual_cluster_to_point_distance = Array.fill[VectorWithNorm](clustersNum)()

        val partial_num = Array.fill[Double](clustersNum)(0)
        val partialDen = Array.fill[Double](clustersNum)(0)
        val numDist = Array.fill[Double](clustersNum)(0)

        data_ponts.foreach { data_point =>
          /**
          * total_distance represents for each data_point the total distance from clusters:
          *
          *      total_distance = SUM_j 1 / ( (||data_point - c_j||)^(2/ (m-1) ) )
          */
          var total_distance = 0.0

          // computation of the distance of data_point from each cluster:
          for (j <- 0 until clustersNum) {
            // the distance of data_point from cluster j:

            // Alex: is this corrent???

            val cluster_to_point_distance =
                        KMeans.fastSquaredDistance(broadcasted_centers.value(j), data_point)
            numDist(j) = math.pow(cluster_to_point_distance, (2/( fuzzynessCoefficient - 1)))

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
          (j, (partial_num(j), partialDen(j)))
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

          var temp: Double = totContr(j)._1 / totContr(j)._2
          //          val newCenter = new VectorWithNorm(totContr(j)._1 / totContr(j)._2)
          val newCenter = centers(j)

          if (KMeans.fastSquaredDistance(newCenter, centers(j)) > epsilon * epsilon) {
            center_changed = true
          }
          centers(j) = newCenter
        }
      }


      if(center_changed == false) {
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

    // Alex: we do not need this!!
    new FuzzyCMeansModel(centers.map(_.vector))
  }

//  private def initRandomCenters(data: RDD[VectorWithNorm])
//  : Array[Array[VectorWithNorm]] = {
//    // Sample all the cluster centers in one pass to avoid repeated scans
//
//    // create a random seed (maybe should be an input like in KMeans??
//    val sample = data.takeSample(true, runs * clustersNum, new XORShiftRandom().nextInt()).toSeq
//    Array.tabulate(runs)(r => sample.slice(r * clustersNum, (r + 1) * clustersNum).map { v =>
//      new VectorWithNorm(Vectors.dense(v.vector.toArray), v.norm)
//    }.toArray)
//  }

  private def initRandomCenters(data: RDD[VectorWithNorm])
  : Array[VectorWithNorm] = {
    // Sample all the cluster centers in one pass to avoid repeated scans

    // create a random seed (maybe should be an input like in KMeans??
    val sample = data.takeSample(true, clustersNum, new XORShiftRandom().nextInt())
    sample
  }

}


object FuzzyCMeans {

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
}