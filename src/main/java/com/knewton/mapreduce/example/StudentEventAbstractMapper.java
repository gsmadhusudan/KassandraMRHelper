/**
 * Copyright 2013, 2014, 2015 Knewton
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 *
 */
package com.knewton.mapreduce.example;

import com.knewton.mapreduce.SSTableCellMapper;
import com.knewton.mapreduce.constant.PropertyConstants;
import com.knewton.mapreduce.util.CounterConstants;
import com.knewton.mapreduce.util.SerializationUtils;
import com.knewton.thrift.StudentEvent;
import com.knewton.thrift.StudentEventData;

import org.apache.cassandra.db.Cell;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.io.WritableComparable;
import org.apache.thrift.TDeserializer;
import org.apache.thrift.TException;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.Interval;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;

import javax.annotation.Nullable;

/**
 * Abstract mapper that converts a cassandra cell to a student event and a cassandra row key to a
 * student id.
 *
 * @author Giannis Neokleous
 *
 * @param <K>
 *            The outgoing key type
 * @param <V>
 *            The outgoing value type
 */
public abstract class StudentEventAbstractMapper<K extends WritableComparable<?>, V extends Writable>
        extends SSTableCellMapper<Long, StudentEvent, K, V> {

    private TDeserializer decoder;
    private Interval timeRange;
    private static final Logger LOG = LoggerFactory.getLogger(StudentEventAbstractMapper.class);

    /**
     * Time date format. Example: 2013-03-31T04:03:02.394-04:00
     */
    public static final String DATE_TIME_STRING_FORMAT = "yyyy'-'MM'-'dd'T'HH':'mm':'ss.SSSZ";

    public StudentEventAbstractMapper() {
        setSkipDeletedAtoms(true);
    }

    /**
     * Setup time range for excluding student events.
     */
    @Override
    protected void setup(Context context) throws IOException, InterruptedException {
        super.setup(context);
        this.decoder = SerializationUtils.getDeserializerFromConf(context.getConfiguration());
        setupTimeRange(context.getConfiguration());
    }

    /**
     * Gets the key
     *
     * @param key Key bytes
     * @param context The context
     * @return Get the student id from the row key.
     */
    @Override
    protected Long getMapperKey(ByteBuffer key, Context context) {
        ByteBuffer dup = key.slice();
        Long studentId = dup.getLong();
        return studentId;
    }

    /**
     * Gets the student event value
     *
     * @param atom The atom to get the value from
     * @param context The context
     * @return Get a student event out of a cassandra atom.
     */
    @Override
    protected StudentEvent getMapperValue(Cell atom, Context context) {
        return getStudentEvent(atom, context);
    }

    /**
     * Make a student event out of a cell in cassandra.
     *
     * @param value
     * @param context
     * @return
     */
    @Nullable
    private StudentEvent getStudentEvent(Cell value, Context context) {
        context.getCounter(CounterConstants.STUDENT_EVENTS_JOB,
                CounterConstants.STUDENT_EVENTS_COUNT).increment(1);
        ByteBuffer cellNameDup = value.name().toByteBuffer().slice();
        ByteBuffer cellValueDup = value.value().slice();
        long eventId = cellNameDup.getLong();
        if (!isInTimeRange(eventId, context)) {
            return null;
        }
        byte[] data = new byte[cellValueDup.remaining()];
        cellValueDup.get(data);
        StudentEvent studentEvent = new StudentEvent();
        StudentEventData studentEventData = new StudentEventData();
        try {
            decoder.deserialize(studentEventData, data);
        } catch (TException e) {
            LOG.error(String.format(
                    "Could not deserialize byte array for event with id %d. Skipping Got: %s",
                    eventId, e.getMessage()));
            return null;
        }
        studentEvent.setId(eventId);
        studentEvent.setData(studentEventData);
        context.getCounter(CounterConstants.STUDENT_EVENTS_JOB,
                CounterConstants.STUDENT_EVENTS_PROCESSED).increment(1);
        return studentEvent;
    }

    /**
     * Checks to see if the event is in the desired time range.
     *
     * @return True if the event is in the configured time interval
     */
    private boolean isInTimeRange(long eventId, Context context) {
        DateTime eventTime = new DateTime(eventId, DateTimeZone.UTC);
        // Skip events outside of the desired time range.
        if (timeRange != null && !timeRange.contains(eventTime)) {
            context.getCounter(CounterConstants.STUDENT_EVENTS_JOB,
                               CounterConstants.STUDENT_EVENTS_SKIPPED).increment(1);
            return false;
        }
        return true;
    }

    /**
     * Sets up a DateTime interval for excluding student events. When start time is not set then it
     * defaults to the beginning of time. If end date is not specified then it defaults to
     * "the end of time".
     */
    private void setupTimeRange(Configuration conf) {
        DateTimeFormatter dtf = DateTimeFormat.forPattern(DATE_TIME_STRING_FORMAT).withZoneUTC();
        String startDateStr = conf.get(PropertyConstants.START_DATE.txt);
        String endDateStr = conf.get(PropertyConstants.END_DATE.txt);
        // No need to instantiate timeRange.
        if (startDateStr == null && endDateStr == null) {
            return;
        }
        DateTime startDate;
        if (startDateStr != null) {
            startDate = dtf.parseDateTime(startDateStr);
        } else {
            startDate = new DateTime(0).year().withMinimumValue().withZone(DateTimeZone.UTC);
        }
        DateTime endDate;
        if (endDateStr != null) {
            endDate = dtf.parseDateTime(endDateStr);
        } else {
            endDate = new DateTime(0).withZone(DateTimeZone.UTC).year().withMaximumValue();
        }
        this.timeRange = new Interval(startDate, endDate);
    }

}
