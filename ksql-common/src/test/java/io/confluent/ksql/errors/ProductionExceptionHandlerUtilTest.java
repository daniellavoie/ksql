/*
 * Copyright 2019 Confluent Inc.
 *
 * Licensed under the Confluent Community License; you may not use this file
 * except in compliance with the License.  You may obtain a copy of the License at
 *
 * http://www.confluent.io/confluent-community-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OF ANY KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package io.confluent.ksql.errors;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableMap;
import io.confluent.common.logging.StructuredLogger;
import io.confluent.ksql.errors.ProductionExceptionHandlerUtil.LogAndContinueProductionExceptionHandler;
import io.confluent.ksql.errors.ProductionExceptionHandlerUtil.LogAndFailProductionExceptionHandler;
import io.confluent.ksql.errors.ProductionExceptionHandlerUtil.LogAndXProductionExceptionHandler;
import io.confluent.ksql.processing.log.ProcessingLogMessageSchema;
import io.confluent.ksql.processing.log.ProcessingLogMessageSchema.MessageType;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.connect.data.SchemaAndValue;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.streams.errors.ProductionExceptionHandler.ProductionExceptionHandlerResponse;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class ProductionExceptionHandlerUtilTest {

  private final static String LOGGER_NAME = "loggerName";
  private final static Map<String, ?> CONFIGS = ImmutableMap.of(
      ProductionExceptionHandlerUtil.KSQL_PRODUCTION_ERROR_LOGGER_NAME, LOGGER_NAME);

  @Mock
  private Function<String, StructuredLogger> loggerFactory;
  @Mock
  private StructuredLogger logger;
  @Captor
  private ArgumentCaptor<Supplier<SchemaAndValue>> msgCaptor;
  @Mock
  private ProducerRecord<byte[], byte[]> record;
  @Mock
  private ProductionExceptionHandlerResponse mockResponse;

  private LogAndXProductionExceptionHandler exceptionHandler;

  @Before
  public void setUp() {
    when(loggerFactory.apply(ArgumentMatchers.anyString())).thenReturn(logger);

    exceptionHandler = new TestLogAndXProductionExceptionHandler(mockResponse);
    exceptionHandler.configure(CONFIGS, loggerFactory);
  }

  @Test
  public void shouldReturnLogAndFailHandler() {
    assertThat(
        ProductionExceptionHandlerUtil.getHandler(true),
        equalTo(LogAndFailProductionExceptionHandler.class));
  }

  @Test
  public void shouldReturnLogAndContinueHandler() {
    assertThat(
        ProductionExceptionHandlerUtil.getHandler(false),
        equalTo(LogAndContinueProductionExceptionHandler.class));
  }

  @Test
  public void shouldSetLoggerNameCorrectly() {
    // Then:
    verify(loggerFactory).apply(LOGGER_NAME);
  }

  @Test
  public void shouldLogErrorCorrectly() {
    // When:
    exceptionHandler.handle(record, new Exception("foo"));

    // Then:
    verify(logger).error(msgCaptor.capture());
    final SchemaAndValue schemaAndValue = msgCaptor.getValue().get();

    assertThat(schemaAndValue.schema(), is(ProcessingLogMessageSchema.PROCESSING_LOG_SCHEMA));
    final Struct msg = (Struct) schemaAndValue.value();
    assertThat(
        msg.get(ProcessingLogMessageSchema.TYPE),
        is(MessageType.PRODUCTION_ERROR.getTypeId()));
    assertThat(msg.get(ProcessingLogMessageSchema.PRODUCTION_ERROR), notNullValue());
    final Struct productionError = msg.getStruct(ProcessingLogMessageSchema.PRODUCTION_ERROR);
    assertThat(productionError.schema(), is(MessageType.PRODUCTION_ERROR.getSchema()));
    assertThat(
        productionError.get(ProcessingLogMessageSchema.PRODUCTION_ERROR_FIELD_MESSAGE), is("foo"));
  }

  @Test
  public void shouldReturnCorrectResponse() {
    assertResponseIs(mockResponse);
  }

  @Test
  public void shouldReturnFailFromLogAndFailHandler() {
    // Given:
    exceptionHandler = new LogAndFailProductionExceptionHandler();
    exceptionHandler.configure(CONFIGS, loggerFactory);

    // Then:
    assertResponseIs(ProductionExceptionHandlerResponse.FAIL);
  }

  @Test
  public void shouldReturnContinueFromLogAndContinueHandler() {
    // Given:
    exceptionHandler = new LogAndContinueProductionExceptionHandler();
    exceptionHandler.configure(CONFIGS, loggerFactory);

    // Then:
    assertResponseIs(ProductionExceptionHandlerResponse.CONTINUE);
  }

  private void assertResponseIs(Object o) {
    // When:
    final ProductionExceptionHandlerResponse response =
        exceptionHandler.handle(record, new Exception());

    // Then:
    assertThat(response, is(o));
  }

  private static class TestLogAndXProductionExceptionHandler extends LogAndXProductionExceptionHandler {

    private final ProductionExceptionHandlerResponse response;

    private TestLogAndXProductionExceptionHandler(ProductionExceptionHandlerResponse response) {
      this.response = response;
    }

    @Override
    ProductionExceptionHandlerResponse getResponse() {
      return response;
    }
  }
}