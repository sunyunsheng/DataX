/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.wgzhao.datax.core.transport.exchanger;

import com.wgzhao.datax.common.element.Record;
import com.wgzhao.datax.common.exception.CommonErrorCode;
import com.wgzhao.datax.common.exception.DataXException;
import com.wgzhao.datax.common.plugin.RecordReceiver;
import com.wgzhao.datax.common.plugin.RecordSender;
import com.wgzhao.datax.common.plugin.TaskPluginCollector;
import com.wgzhao.datax.common.util.Configuration;
import com.wgzhao.datax.core.statistics.communication.Communication;
import com.wgzhao.datax.core.transport.channel.Channel;
import com.wgzhao.datax.core.transport.record.TerminateRecord;
import com.wgzhao.datax.core.transport.transformer.TransformerExecution;
import com.wgzhao.datax.core.util.FrameworkErrorCode;
import com.wgzhao.datax.core.util.container.CoreConstant;
import org.apache.commons.lang.Validate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class BufferedRecordTransformerExchanger
        extends TransformerExchanger
        implements RecordSender, RecordReceiver
{

    private static Class<? extends Record> RECORD_CLASS;
    protected final int byteCapacity;
    private final Channel channel;
    private final List<Record> buffer;
    private final AtomicInteger memoryBytes = new AtomicInteger(0);
    private int bufferSize;
    private int bufferIndex = 0;
    private volatile boolean shutdown = false;

    @SuppressWarnings("unchecked")
    public BufferedRecordTransformerExchanger(int taskGroupId, int taskId,
            Channel channel, Communication communication,
            TaskPluginCollector pluginCollector,
            List<TransformerExecution> tInfoExecs)
    {
        super(taskGroupId, taskId, communication, tInfoExecs, pluginCollector);
        assert null != channel;
        assert null != channel.getConfiguration();

        this.channel = channel;
        Configuration configuration = channel.getConfiguration();

        this.bufferSize = configuration
                .getInt(CoreConstant.DATAX_CORE_TRANSPORT_EXCHANGER_BUFFERSIZE);
        this.buffer = new ArrayList<>(bufferSize);

        //channel的queue默认大小为8M，原来为64M
        this.byteCapacity = configuration.getInt(
                CoreConstant.DATAX_CORE_TRANSPORT_CHANNEL_CAPACITY_BYTE, 8 * 1024 * 1024);

        try {
            BufferedRecordTransformerExchanger.RECORD_CLASS = ((Class<? extends Record>) Class
                    .forName(configuration.getString(
                            CoreConstant.DATAX_CORE_TRANSPORT_RECORD_CLASS,
                            "com.wgzhao.datax.core.transport.record.DefaultRecord")));
        }
        catch (Exception e) {
            throw DataXException.asDataXException(
                    FrameworkErrorCode.CONFIG_ERROR, e);
        }
    }

    @Override
    public Record createRecord()
    {
        try {
            return BufferedRecordTransformerExchanger.RECORD_CLASS.newInstance();
        }
        catch (Exception e) {
            throw DataXException.asDataXException(
                    FrameworkErrorCode.CONFIG_ERROR, e);
        }
    }

    @Override
    public void sendToWriter(Record record)
    {
        if (shutdown) {
            throw DataXException.asDataXException(CommonErrorCode.SHUT_DOWN_TASK, "");
        }

        Validate.notNull(record, "record不能为空.");

        record = doTransformer(record);

        if (record == null) {
            return;
        }

        if (record.getMemorySize() > this.byteCapacity) {
            this.pluginCollector.collectDirtyRecord(record,
                    new Exception(String.format("单条记录超过大小限制，当前限制为:%s", this.byteCapacity)));
            return;
        }

        boolean isFull = (this.bufferIndex >= this.bufferSize
                || this.memoryBytes.get() + record.getMemorySize() > this.byteCapacity);
        if (isFull) {
            flush();
        }

        this.buffer.add(record);
        this.bufferIndex++;
        memoryBytes.addAndGet(record.getMemorySize());
    }

    @Override
    public void flush()
    {
        if (shutdown) {
            throw DataXException.asDataXException(CommonErrorCode.SHUT_DOWN_TASK, "");
        }
        this.channel.pushAll(this.buffer);
        //和channel的统计保持同步
        doStat();
        this.buffer.clear();
        this.bufferIndex = 0;
        this.memoryBytes.set(0);
    }

    @Override
    public void terminate()
    {
        if (shutdown) {
            throw DataXException.asDataXException(CommonErrorCode.SHUT_DOWN_TASK, "");
        }
        flush();
        this.channel.pushTerminate(TerminateRecord.get());
    }

    @Override
    public Record getFromReader()
    {
        if (shutdown) {
            throw DataXException.asDataXException(CommonErrorCode.SHUT_DOWN_TASK, "");
        }
        boolean isEmpty = (this.bufferIndex >= this.buffer.size());
        if (isEmpty) {
            receive();
        }

        Record record = this.buffer.get(this.bufferIndex++);
        if (record instanceof TerminateRecord) {
            record = null;
        }
        return record;
    }

    @Override
    public void shutdown()
    {
        shutdown = true;
        try {
            buffer.clear();
            channel.clear();
        }
        catch (Throwable t) {
            t.printStackTrace();
        }
    }

    private void receive()
    {
        this.channel.pullAll(this.buffer);
        this.bufferIndex = 0;
        this.bufferSize = this.buffer.size();
    }
}
