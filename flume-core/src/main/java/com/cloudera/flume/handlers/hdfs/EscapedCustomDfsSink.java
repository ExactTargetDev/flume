/**
 * Licensed to Cloudera, Inc. under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  Cloudera, Inc. licenses this file
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
package com.cloudera.flume.handlers.hdfs;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.hadoop.fs.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cloudera.flume.conf.Context;
import com.cloudera.flume.conf.FlumeBuilder.FunctionSpec;
import com.cloudera.flume.conf.FlumeBuilder;
import com.cloudera.flume.conf.FlumeConfiguration;
import com.cloudera.flume.conf.FlumeSpecException;
import com.cloudera.flume.conf.SinkBuilderUtil;
import com.cloudera.flume.conf.SinkFactory.SinkBuilder;
import com.cloudera.flume.core.Event;
import com.cloudera.flume.core.EventSink;
import com.cloudera.flume.handlers.text.FormatFactory;
import com.cloudera.flume.handlers.text.output.OutputFormat;
import com.cloudera.flume.handlers.text.output.RawOutputFormat;
import com.google.common.base.Preconditions;

/**
 * Writes events the a file give a hadoop uri path. If no uri is specified It
 * defaults to the set by the given configured by fs.default.name config
 * variable. The user can specify an output format for the file. If none is
 * specified the default set by flume.collector.outputformat in the
 * flumeconfiguration file is used.
 * 
 * TODO (jon) eventually, the CustomDfsSink should be replaced with just a
 * EventSink.
 * 
 * TODO (jon) this is gross, please deprecate me.
 */
public class EscapedCustomDfsSink extends EventSink.Base {
  static final Logger LOG = LoggerFactory.getLogger(EscapedCustomDfsSink.class);
  final String path;
  FunctionSpec formatSpec;
  Context ctx;

  CustomDfsSink writer = null;

  // We keep a - potentially unbounded - set of writers around to deal with
  // different tags on events. Therefore this feature should be used with some
  // care (where the set of possible paths is small) until we do something
  // more sensible with resource management.
  final Map<String, CustomDfsSink> sfWriters = new HashMap<String, CustomDfsSink>();

  // Used to short-circuit around doing regex matches when we know there are
  // no templates to be replaced.
  boolean shouldSub = false;
  private String filename = "";
  protected String absolutePath = "";

  public EscapedCustomDfsSink(Context ctx, String path, String filename,
      FunctionSpec fs) {
    Preconditions.checkNotNull(ctx);
    this.path = path;
    this.filename = filename;
    this.ctx = ctx;
    shouldSub = Event.containsTag(path) || Event.containsTag(filename);
    this.formatSpec = fs;
    absolutePath = path;
    if (filename != null && filename.length() > 0) {
      if (!absolutePath.endsWith(Path.SEPARATOR)) {
        absolutePath += Path.SEPARATOR;
      }
      absolutePath += this.filename;
    }
  }

  static protected OutputFormat getDefaultOutputFormat() {
    try {
      return FormatFactory.get().getOutputFormat(
          FlumeConfiguration.get().getDefaultOutputFormat());
    } catch (FlumeSpecException e) {
      LOG.warn("format from conf file not found, using default", e);
      return new RawOutputFormat();
    }
  }

  public EscapedCustomDfsSink(Context ctx, String path, String filename) {
    this(ctx, path, filename, SinkBuilderUtil.getDefaultOutputFormatSpec());
  }

  protected CustomDfsSink openWriter(String p) throws IOException {
    LOG.info("Opening " + p);
    // We need to instantiate a new outputFormat for each CustomDfsSink.
    OutputFormat format;
    try {
      format = SinkBuilderUtil.resolveOutputFormat(ctx, formatSpec);
    } catch (FlumeSpecException e) {
      format = getDefaultOutputFormat();
      LOG.warn("Had problem creating format " + formatSpec
          + "; reverting to default:" + format);
    }
    CustomDfsSink w = new CustomDfsSink(p, format);
    w.open();
    return w;
  }

  /**
   * Writes the message to an HDFS file whose path is substituted with tags
   * drawn from the supplied event
   */
  @Override
  public void append(Event e) throws IOException, InterruptedException {
    CustomDfsSink w = writer;
    if (shouldSub) {
      String realPath = e.escapeString(absolutePath);
      w = sfWriters.get(realPath);
      if (w == null) {
        w = openWriter(realPath);
        sfWriters.put(realPath, w);
      }
    }
    w.append(e);
    super.append(e);
  }

  @Override
  public void close() throws IOException {
    if (shouldSub) {
      for (Entry<String, CustomDfsSink> e : sfWriters.entrySet()) {
        LOG.info("Closing " + e.getKey());
        e.getValue().close();
      }
    } else {
      LOG.info("Closing " + absolutePath);
      if (writer == null) {
        LOG.warn("EscapedCustomDfsSink's Writer for '" + absolutePath
            + "' was already closed!");
        return;
      }

      writer.close();
      writer = null;
    }
  }

  @Override
  public void open() throws IOException {
    if (!shouldSub) {
      writer = openWriter(absolutePath);
    }
  }

  public static SinkBuilder builder() {
    return new SinkBuilder() {
      @Override
      public EventSink create(Context context, Object... args) {
        Preconditions.checkArgument(args.length >= 1 && args.length <= 3,
            "usage: escapedCustomDfs(\"[(hdfs|file|s3n|...)://namenode[:port]]/path\""
                + "[, file [,outputformat ]])");

        String filename = "";
        if (args.length >= 2) {
          filename = args[1].toString();
        }

        Object formatArg = null;
        if (args.length >= 3) {
          formatArg = args[2];
        }

        OutputFormat o = null;
        try {
          o = SinkBuilderUtil.resolveOutputFormat(context, formatArg);
        } catch (FlumeSpecException e) {
          LOG.warn("Illegal format type " + formatArg + ".", e);
        }
        Preconditions.checkArgument(o != null, "Illegal format type "
            + formatArg + ".");

        // handle legacy string format arguments 
        // TODO only support FunctionSpec in the future
        if (formatArg instanceof String) {
          formatArg = new FunctionSpec((String) formatArg);
        }
        FunctionSpec formatFunc = (FunctionSpec) formatArg;
        return new EscapedCustomDfsSink(context, args[0].toString(), filename,
            formatFunc);
      }

      @Deprecated
      @Override
      public EventSink build(Context context, String... args) {
        // updated interface calls build(Context,Object...) instead
        throw new RuntimeException(
            "Old sink builder for EscapedCustomDfsSink should not be exercised");
      }

    };
  }
}
