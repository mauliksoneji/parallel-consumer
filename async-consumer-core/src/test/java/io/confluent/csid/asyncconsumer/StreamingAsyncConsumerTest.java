package io.confluent.csid.asyncconsumer;

import io.confluent.csid.asyncconsumer.AsyncConsumer.ConsumeProduceResult;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.assertj.core.util.Lists;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.time.Duration.ofMillis;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.internal.verification.VerificationModeFactory.times;

// TODO this class shouldn't have access to the non streaming async consumer - refactor out another super class layer
@Slf4j
public class StreamingAsyncConsumerTest extends AsyncConsumerTestBase {

    StreamingAsyncConsumer<String, String> streaming;

    @BeforeEach
    public void setupData() {
        super.primeFirstRecord();
    }

    @Override
    protected AsyncConsumer initAsyncConsumer(AsyncConsumerOptions asyncConsumerOptions) {
        AsyncConsumerOptions options = AsyncConsumerOptions.builder().build();
        streaming = new StreamingAsyncConsumer<>(consumerSpy, producerSpy, options);

        return streaming;
    }

    @Test
    public void testStream() {
        var latch = new CountDownLatch(1);
        Stream<ConsumeProduceResult<String, String, String, String>> streamedResults = streaming.asyncPollProduceAndStream((record) -> {
            ProducerRecord mock = mock(ProducerRecord.class);
            log.info("Consumed and produced record ({}), and returning a derivative result to produce to output topic: {}", record, mock);
            myRecordProcessingAction.apply(record);
            latch.countDown();
            return Lists.list(mock);
        });

        awaitLatch(latch);

        waitForSomeLoopCycles(2);

        verify(myRecordProcessingAction, times(1)).apply(any());

        Stream<ConsumeProduceResult<String, String, String, String>> peekedStream = streamedResults.peek(x ->
        {
            log.info("streaming test {}", x.getIn().value());
        });

        assertThat(peekedStream).hasSize(1);
    }

    @Test
    public void testConsumeAndProduce() {
        var latch = new CountDownLatch(1);
        var stream = streaming.asyncPollProduceAndStream((record) -> {
            String apply = myRecordProcessingAction.apply(record);
            ProducerRecord<String, String> result = new ProducerRecord<>(OUTPUT_TOPIC, "akey", apply);
            log.info("Consumed and a record ({}), and returning a derivative result record to be produced: {}", record, result);
            List<ProducerRecord<String, String>> result1 = Lists.list(result);
            latch.countDown();
            return result1;
        });

        pauseControlLoop();

        awaitLatch(latch);

        resumeControlLoop();

        waitForSomeLoopCycles(1);

        verify(myRecordProcessingAction, times(1)).apply(any());

        var myResultStream = stream.peek(x -> {
            if (x != null) {
                ConsumerRecord<String, String> left = x.getIn();
                log.info("{}:{}:{}:{}", left.key(), left.value(), x.getOut(), x.getMeta());
            } else {
                log.info("null");
            }
        });

        var collect = myResultStream.collect(Collectors.toList());

        assertThat(collect).hasSize(1);
    }


    @Test
    public void testFlatMapProduce() {
        var latch = new CountDownLatch(1);
        var myResultStream = streaming.asyncPollProduceAndStream((record) -> {
            String apply1 = myRecordProcessingAction.apply(record);
            String apply2 = myRecordProcessingAction.apply(record);

            var list = Lists.list(
                    new ProducerRecord<>(OUTPUT_TOPIC,"key", apply1),
                    new ProducerRecord<>(OUTPUT_TOPIC,"key", apply2));

            latch.countDown();
            return list;
        });

        awaitLatch(latch);

        waitForSomeLoopCycles(1);

        verify(myRecordProcessingAction, times(2)).apply(any());

        assertThat(myResultStream).hasSize(2);
    }

}