/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.horn.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hama.HamaConfiguration;
import org.apache.hama.bsp.BSP;
import org.apache.hama.bsp.BSPPeer;
import org.apache.hama.bsp.sync.SyncException;
import org.apache.hama.commons.io.VectorWritable;
import org.apache.hama.commons.math.DenseDoubleMatrix;
import org.apache.hama.commons.math.DoubleMatrix;
import org.apache.hama.commons.math.DoubleVector;

/**
 * The trainer that train the {@link LayeredNeuralNetwork} based on BSP
 * framework.
 * 
 */
public final class LayeredNeuralNetworkTrainer
    extends
    BSP<LongWritable, VectorWritable, NullWritable, NullWritable, ParameterMessage> {

  private static final Log LOG = LogFactory
      .getLog(LayeredNeuralNetworkTrainer.class);

  private LayeredNeuralNetwork inMemoryModel;
  private HamaConfiguration conf;
  /* Default batch size */
  private int batchSize;

  /* check the interval between intervals */
  private double prevAvgTrainingError;
  private double curAvgTrainingError;
  private long convergenceCheckInterval;
  private long iterations;
  private long maxIterations;
  private long epoch;
  private boolean isConverge;

  private String modelPath;

  @Override
  /**
   * If the model path is specified, load the existing from storage location.
   */
  public void setup(
      BSPPeer<LongWritable, VectorWritable, NullWritable, NullWritable, ParameterMessage> peer) {
    if (peer.getPeerIndex() == 0) {
      LOG.info("Begin to train");
    }
    this.isConverge = false;
    this.conf = peer.getConfiguration();
    this.iterations = 0;
    this.epoch = 0;
    this.modelPath = conf.get("model.path");
    this.maxIterations = conf.getLong("training.max.iterations", 100000);
    this.convergenceCheckInterval = conf.getLong("convergence.check.interval",
        100);
    this.inMemoryModel = new LayeredNeuralNetwork(conf, modelPath);
    this.prevAvgTrainingError = Integer.MAX_VALUE;
    this.batchSize = conf.getInt("training.batch.size", 50);
  }

  @Override
  /**
   * Write the trained model back to stored location.
   */
  public void cleanup(
      BSPPeer<LongWritable, VectorWritable, NullWritable, NullWritable, ParameterMessage> peer) {
    // write model to modelPath
    if (peer.getPeerIndex() == 0) {
      try {
        LOG.info(String.format("End of training, number of iterations: %d.",
            this.iterations));
        LOG.info(String.format("Write model back to %s",
            inMemoryModel.getModelPath()));
        this.inMemoryModel.writeModelToFile();
      } catch (IOException e) {
        e.printStackTrace();
      }
    }
  }

  private List<DoubleVector> trainingSet = new ArrayList<DoubleVector>();
  private Random r = new Random();

  @Override
  public void bsp(
      BSPPeer<LongWritable, VectorWritable, NullWritable, NullWritable, ParameterMessage> peer)
      throws IOException, SyncException, InterruptedException {
    // load local data into memory
    LongWritable key = new LongWritable();
    VectorWritable value = new VectorWritable();
    while (peer.readNext(key, value)) {
      DoubleVector v = value.getVector();
      trainingSet.add(v);
    }

    while (this.iterations++ < maxIterations) {
      // each groom calculate the matrices updates according to local data
      if (peer.getPeerIndex() != peer.getNumPeers() - 1) {
        calculateUpdates(peer);
      } else {
        // doing summation received updates
        if (peer.getSuperstepCount() > 0) {
          // and broadcasts previous updated weights
          mergeUpdates(peer);
        }
      }
      
      peer.sync();
      
      if(isConverge) {
        if(peer.getPeerIndex() == peer.getNumPeers() - 1)
          peer.sync();
        break;
      }
    }
  }

  private DoubleVector getRandomInstance() {
    return trainingSet.get(r.nextInt(trainingSet.size()));
  }

  /**
   * Calculate the matrices updates according to local partition of data.
   * 
   * @param peer
   * @throws IOException
   */
  private void calculateUpdates(
      BSPPeer<LongWritable, VectorWritable, NullWritable, NullWritable, ParameterMessage> peer)
      throws IOException {
    // receive update information from master
    if (peer.getNumCurrentMessages() != 0) {
      ParameterMessage inMessage = peer.getCurrentMessage();
      DoubleMatrix[] newWeights = inMessage.getCurMatrices();
      DoubleMatrix[] preWeightUpdates = inMessage.getPrevMatrices();
      this.inMemoryModel.setWeightMatrices(newWeights);
      this.inMemoryModel.setPrevWeightMatrices(preWeightUpdates);
      this.isConverge = inMessage.isConverge();
      // check converge
      if (isConverge) {
        return;
      }
    }

    DoubleMatrix[] weightUpdates = new DoubleMatrix[this.inMemoryModel.weightMatrixList
        .size()];
    for (int i = 0; i < weightUpdates.length; ++i) {
      int row = this.inMemoryModel.weightMatrixList.get(i).getRowCount();
      int col = this.inMemoryModel.weightMatrixList.get(i).getColumnCount();
      weightUpdates[i] = new DenseDoubleMatrix(row, col);
    }

    // continue to train
    double avgTrainingError = 0.0;
    for (int recordsRead = 0; recordsRead < batchSize; ++recordsRead) {
      DoubleVector trainingInstance = getRandomInstance();
      LayeredNeuralNetwork.matricesAdd(weightUpdates,
          this.inMemoryModel.trainByInstance(trainingInstance));
      avgTrainingError += this.inMemoryModel.trainingError;
    }
    avgTrainingError /= batchSize;

    // calculate the average of updates
    for (int i = 0; i < weightUpdates.length; ++i) {
      weightUpdates[i] = weightUpdates[i].divide(batchSize);
    }

    DoubleMatrix[] prevWeightUpdates = this.inMemoryModel
        .getPrevMatricesUpdates();
    ParameterMessage outMessage = new ParameterMessage(avgTrainingError, false,
        weightUpdates, prevWeightUpdates);
    peer.send(peer.getPeerName(peer.getNumPeers() - 1), outMessage);
  }

  /**
   * Merge the updates according to the updates of the grooms.
   * 
   * @param peer
   * @throws IOException
   */
  private void mergeUpdates(
      BSPPeer<LongWritable, VectorWritable, NullWritable, NullWritable, ParameterMessage> peer)
      throws IOException {
    int numMessages = peer.getNumCurrentMessages();
    boolean converge = false;
    if (numMessages == 0) { // converges
      converge = true;
      return;
    }

    double avgTrainingError = 0;
    DoubleMatrix[] matricesUpdates = null;
    DoubleMatrix[] prevMatricesUpdates = null;

    while (peer.getNumCurrentMessages() > 0) {
      ParameterMessage message = peer.getCurrentMessage();
      if (matricesUpdates == null) {
        matricesUpdates = message.getCurMatrices();
        prevMatricesUpdates = message.getPrevMatrices();
      } else {
        LayeredNeuralNetwork.matricesAdd(matricesUpdates,
            message.getCurMatrices());
        LayeredNeuralNetwork.matricesAdd(prevMatricesUpdates,
            message.getPrevMatrices());
      }

      avgTrainingError += message.getTrainingError();
    }

    if (numMessages > 1) {
      avgTrainingError /= numMessages;
      for (int i = 0; i < matricesUpdates.length; ++i) {
        matricesUpdates[i] = matricesUpdates[i].divide(numMessages);
        prevMatricesUpdates[i] = prevMatricesUpdates[i].divide(numMessages);
      }
    }

    this.inMemoryModel.updateWeightMatrices(matricesUpdates);
    this.inMemoryModel.setPrevWeightMatrices(prevMatricesUpdates);

    // check convergence
    if (peer.getSuperstepCount() > 0
        && iterations % convergenceCheckInterval == 0) {
      if (prevAvgTrainingError < curAvgTrainingError) {
        // error cannot decrease any more
        converge = true;
      }
      // update
      prevAvgTrainingError = curAvgTrainingError;
      LOG.info("Training error: " + curAvgTrainingError + " at " + (iterations)
          + " iteration.");
      curAvgTrainingError = 0;
    }
    curAvgTrainingError += avgTrainingError / convergenceCheckInterval;
    this.isConverge = converge;
    
    // broadcast updated weight matrices
    for (String peerName : peer.getAllPeerNames()) {
      ParameterMessage msg = new ParameterMessage(0, converge,
          this.inMemoryModel.getWeightMatrices(),
          this.inMemoryModel.getPrevMatricesUpdates());
      if (!peer.getPeerName().equals(peerName))
        peer.send(peerName, msg);
    }
  }

}
