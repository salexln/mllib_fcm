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

import scala.collection.JavaConverters._

import org.json4s._
import org.json4s.JsonDSL._
import org.json4s.jackson.JsonMethods._

import org.apache.spark.annotation.Since
import org.apache.spark.api.java.JavaRDD
import org.apache.spark.mllib.linalg.Vector
import org.apache.spark.mllib.pmml.PMMLExportable
import org.apache.spark.mllib.util.{Loader, Saveable}
import org.apache.spark.rdd.RDD
import org.apache.spark.SparkContext
import org.apache.spark.sql.SQLContext
import org.apache.spark.sql.Row
/**
  * A clustering model for Fuzzy C - Means
  */
class FuzzyCMeansModel @Since("1.1.0") (@Since("1.0.0") val clusterCenters: Array[Vector])
  extends Saveable with Serializable with PMMLExportable {


  override protected def formatVersion: String = "1.0"

  override def save(sc: SparkContext, path: String): Unit = {
    // FuzzyCMeansModel.SaveLoadV1_0.save(sc, this, path)
  }
  /**
   * A Java-friendly constructor that takes an Iterable of Vectors.
   */
  @Since("1.4.0")
  def this(centers: java.lang.Iterable[Vector]) = this(centers.asScala.toArray)

  /**
   * Total number of clusters.
   */
  def clusterCentersNum() : Int = clusterCenters.length

  def centers(): Array[Vector] = {
    clusterCenters
  }

  /**
   *
   * @param data_point the data point you want to get the membership vector for
   * @return the membership vector for the input point: defines the membership
   *         of the input point to each cluster
   */
  def getMembershipForPoint(data_point: VectorWithNorm): Array[Double] = {
    val ui = Array.fill[Double](clusterCentersNum())(0)
    val clusterCenters = centers()
    val distance_from_centerArr = Array.fill[Double](clusterCentersNum())(0)


    // compute distances:
    var total_distance = 0.0
    for (j <- 0 until clusterCentersNum()) {
      val center_with_norm = new VectorWithNorm(clusterCenters(j))
      val distance_from_center = KMeans.fastSquaredDistance(center_with_norm, data_point)
      val temp = math.pow(distance_from_center,
                                2/( FuzzyCMeans.getFuzzynessCoefficient - 1))

      distance_from_centerArr(j) = temp
      total_distance += (1 / distance_from_centerArr(j))
    }

    // compute the u_i_j:
    for (j <- 0 until clusterCentersNum()) {
      val u_i_j_m: Double = math.pow(distance_from_centerArr(j) * total_distance,
        -FuzzyCMeans.getFuzzynessCoefficient)
      ui(j) = u_i_j_m
    }
    ui
  }
}