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

package org.apache.spark.scheduler

import java.io.{DataInputStream, DataOutputStream}
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.Properties

import scala.collection.JavaConverters._
import scala.collection.immutable
import scala.collection.mutable.{ArrayBuffer, HashMap, Map}

import org.apache.spark.resource.ResourceInformation
import org.apache.spark.util.{ByteBufferInputStream, ByteBufferOutputStream, Utils}

/**
 * Description of a task that gets passed onto executors to be executed, usually created by
 * `TaskSetManager.resourceOffer`.
 *
 * TaskDescriptions and the associated Task need to be serialized carefully for two reasons:
 *
 *     (1) When a TaskDescription is received by an Executor, the Executor needs to first get the
 *         list of JARs and files and add these to the classpath, and set the properties, before
 *         deserializing the Task object (serializedTask). This is why the Properties are included
 *         in the TaskDescription, even though they're also in the serialized task.
 *     (2) Because a TaskDescription is serialized and sent to an executor for each task, efficient
 *         serialization (both in terms of serialization time and serialized buffer size) is
 *         important. For this reason, we serialize TaskDescriptions ourselves with the
 *         TaskDescription.encode and TaskDescription.decode methods.  This results in a smaller
 *         serialized size because it avoids serializing unnecessary fields in the Map objects
 *         (which can introduce significant overhead when the maps are small).
  *
  *
  *         传递给要执行的执行者的任务的描述，通常由
“TaskSetManager.resourceOffer”。

taskdescription和相关任务需要仔细序列化，原因有二:

(1)当一个TaskDescription被一个Executor接收时，Executor需要首先获取
jar和文件的列表，并将它们添加到类路径中，并在前面设置属性
反序列化任务对象(serializedTask)。这就是包含属性的原因
在任务描述中，即使它们也在序列化任务中。
(2)因为TaskDescription被序列化并发送给每个任务的执行者，所以效率很高
序列化(在序列化时间和序列化缓冲区大小方面)是
重要的。为此，我们使用
TaskDescription。编码和任务描述。解码方法。这导致了一个较小的
序列化的大小，因为它避免序列化映射对象中不必要的字段
(当映射很小时，这会带来很大的开销)。
 */
private[spark] class TaskDescription(
    val taskId: Long,
    val attemptNumber: Int,
    val executorId: String,
    val name: String,
    val index: Int,    // Index within this task's TaskSet
    val partitionId: Int,
    val addedFiles: Map[String, Long],
    val addedJars: Map[String, Long],
    val properties: Properties,
    val resources: immutable.Map[String, ResourceInformation],
    val serializedTask: ByteBuffer) {

  override def toString: String = "TaskDescription(TID=%d, index=%d)".format(taskId, index)
}

private[spark] object TaskDescription {
  private def serializeStringLongMap(map: Map[String, Long], dataOut: DataOutputStream): Unit = {
    dataOut.writeInt(map.size)
    map.foreach { case (key, value) =>
      dataOut.writeUTF(key)
      dataOut.writeLong(value)
    }
  }

  private def serializeResources(map: immutable.Map[String, ResourceInformation],
      dataOut: DataOutputStream): Unit = {
    dataOut.writeInt(map.size)
    map.foreach { case (key, value) =>
      dataOut.writeUTF(key)
      dataOut.writeUTF(value.name)
      dataOut.writeInt(value.addresses.size)
      value.addresses.foreach(dataOut.writeUTF(_))
    }
  }

  def encode(taskDescription: TaskDescription): ByteBuffer = {
    val bytesOut = new ByteBufferOutputStream(4096)
    val dataOut = new DataOutputStream(bytesOut)

    dataOut.writeLong(taskDescription.taskId)
    dataOut.writeInt(taskDescription.attemptNumber)
    dataOut.writeUTF(taskDescription.executorId)
    dataOut.writeUTF(taskDescription.name)
    dataOut.writeInt(taskDescription.index)
    dataOut.writeInt(taskDescription.partitionId)

    // Write files.
    serializeStringLongMap(taskDescription.addedFiles, dataOut)

    // Write jars.
    serializeStringLongMap(taskDescription.addedJars, dataOut)

    // Write properties.
    dataOut.writeInt(taskDescription.properties.size())
    taskDescription.properties.asScala.foreach { case (key, value) =>
      dataOut.writeUTF(key)
      // SPARK-19796 -- writeUTF doesn't work for long strings, which can happen for property values
      val bytes = value.getBytes(StandardCharsets.UTF_8)
      dataOut.writeInt(bytes.length)
      dataOut.write(bytes)
    }

    // Write resources.
    serializeResources(taskDescription.resources, dataOut)

    // Write the task. The task is already serialized, so write it directly to the byte buffer.
    Utils.writeByteBuffer(taskDescription.serializedTask, bytesOut)

    dataOut.close()
    bytesOut.close()
    bytesOut.toByteBuffer
  }

  private def deserializeStringLongMap(dataIn: DataInputStream): HashMap[String, Long] = {
    val map = new HashMap[String, Long]()
    val mapSize = dataIn.readInt()
    var i = 0
    while (i < mapSize) {
      map(dataIn.readUTF()) = dataIn.readLong()
      i += 1
    }
    map
  }

  private def deserializeResources(dataIn: DataInputStream):
      immutable.Map[String, ResourceInformation] = {
    val map = new HashMap[String, ResourceInformation]()
    val mapSize = dataIn.readInt()
    var i = 0
    while (i < mapSize) {
      val resType = dataIn.readUTF()
      val name = dataIn.readUTF()
      val numIdentifier = dataIn.readInt()
      val identifiers = new ArrayBuffer[String](numIdentifier)
      var j = 0
      while (j < numIdentifier) {
        identifiers += dataIn.readUTF()
        j += 1
      }
      map(resType) = new ResourceInformation(name, identifiers.toArray)
      i += 1
    }
    map.toMap
  }

  def decode(byteBuffer: ByteBuffer): TaskDescription = {
    val dataIn = new DataInputStream(new ByteBufferInputStream(byteBuffer))
    val taskId = dataIn.readLong()
    val attemptNumber = dataIn.readInt()
    val executorId = dataIn.readUTF()
    val name = dataIn.readUTF()
    val index = dataIn.readInt()
    val partitionId = dataIn.readInt()

    // Read files.
    val taskFiles = deserializeStringLongMap(dataIn)

    // Read jars.
    val taskJars = deserializeStringLongMap(dataIn)

    // Read properties.
    val properties = new Properties()
    val numProperties = dataIn.readInt()
    for (i <- 0 until numProperties) {
      val key = dataIn.readUTF()
      val valueLength = dataIn.readInt()
      val valueBytes = new Array[Byte](valueLength)
      dataIn.readFully(valueBytes)
      properties.setProperty(key, new String(valueBytes, StandardCharsets.UTF_8))
    }

    // Read resources.
    val resources = deserializeResources(dataIn)

    // Create a sub-buffer for the serialized task into its own buffer (to be deserialized later).
    val serializedTask = byteBuffer.slice()

    new TaskDescription(taskId, attemptNumber, executorId, name, index, partitionId, taskFiles,
      taskJars, properties, resources, serializedTask)
  }
}
