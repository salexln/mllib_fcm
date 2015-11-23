import org.apache.spark.Logging
import org.apache.spark.mllib.clustering.KMeans


class FuzzyCMeans_new(k: Int,
                      maxIterations: Int,
                      runs: Int,
                      initializationMode: String,
                      initializationSteps: Int,
                      epsilon: Double,
                      seed: Long) extends KMeans(k, maxIterations, runs,
                                                 initializationMode, initializationSteps,
                                                 epsilon, seed) {

  }