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

package org.apache.spark.sql.hive.thriftserver

import org.apache.commons.logging.LogFactory
import org.apache.hadoop.hive.conf.HiveConf
import org.apache.hadoop.hive.ql.session.SessionState
import org.apache.hive.service.cli.thrift.ThriftBinaryCLIService
import org.apache.hive.service.server.{HiveServer2, ServerOptionsProcessor}
import org.apache.spark.sql.Logging
import org.apache.spark.sql.hive.HiveContext
import org.apache.spark.sql.hive.thriftserver.ReflectionUtils._

/**
 * The main entry point for the Spark SQL port of HiveServer2.  Starts up a `SparkSQLContext` and a
 * `HiveThriftServer2` thrift server.
 */
object HiveThriftServer2 extends Logging {
  var LOG = LogFactory.getLog(classOf[HiveServer2])

  def main(args: Array[String]) {
    val optionsProcessor = new ServerOptionsProcessor("HiveThriftServer2")

    if (!optionsProcessor.process(args)) {
      logger.warn("Error starting HiveThriftServer2 with given arguments")
      System.exit(-1)
    }

    val ss = new SessionState(new HiveConf(classOf[SessionState]))

    // Set all properties specified via command line.
    val hiveConf: HiveConf = ss.getConf

    SessionState.start(ss)

    logger.info("Starting SparkContext")
    SparkSQLEnv.init()
    SessionState.start(ss)

    Runtime.getRuntime.addShutdownHook(
      new Thread() {
        override def run() {
          SparkSQLEnv.sparkContext.stop()
        }
      }
    )

    try {
      val server = new HiveThriftServer2(SparkSQLEnv.hiveContext)
      server.init(hiveConf)
      server.start()
      logger.info("HiveThriftServer2 started")
    } catch {
      case e: Exception =>
        logger.error("Error starting HiveThriftServer2", e)
        System.exit(-1)
    }
  }
}

class HiveThriftServer2(hiveContext: HiveContext)
  extends HiveServer2
  with ReflectedCompositeService {

  override def init(hiveConf: HiveConf) {
    val sparkSqlCliService = new SparkSQLCLIService(hiveContext)
    setSuperField(this, "cliService", sparkSqlCliService)
    addService(sparkSqlCliService)

    val thriftCliService = new ThriftBinaryCLIService(sparkSqlCliService)
    setSuperField(this, "thriftCLIService", thriftCliService)
    addService(thriftCliService)

    initCompositeService(hiveConf)
  }
}
