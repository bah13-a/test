package tn.vas.service;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tn.vas.domain.Enums.Priority;

/**
 * Files RabbitMQ durables, une par priorité, avec dead-letter (vas.mt.dlq). Les messages perdus (crash entre commit et
 * publish) sont repris par le balayeur MtSweeper à partir de la base : la base reste la source de vérité.
 */
@Component
@ConditionalOnProperty(name = "vas.queue", havingValue = "rabbit")
public class RabbitMtQueue implements MtQueue {
    private final RabbitTemplate rabbit;

    public RabbitMtQueue(RabbitTemplate rabbit) {
        this.rabbit = rabbit;
    }

    @Override
    public void publish(String correlationId, Priority priority) {
        Runnable r = () -> rabbit.convertAndSend("vas.mt", "mt." + priority.name().toLowerCase(), correlationId);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { r.run(); }
            });
        } else {
            r.run();
        }
    }

    @Configuration
    @ConditionalOnProperty(name = "vas.queue", havingValue = "rabbit")
    static class Topology {
        @Bean DirectExchange mtExchange() { return new DirectExchange("vas.mt", true, false); }
        @Bean Queue mtDlq() { return QueueBuilder.durable("vas.mt.dlq").quorum().build(); }
        @Bean Queue mtTransactional() { return mtQueue("transactional"); }
        @Bean Queue mtConfirmation() { return mtQueue("confirmation"); }
        @Bean Queue mtBulk() { return mtQueue("bulk"); }
        @Bean Binding b1(DirectExchange ex) { return BindingBuilder.bind(mtTransactional()).to(ex).with("mt.transactional"); }
        @Bean Binding b2(DirectExchange ex) { return BindingBuilder.bind(mtConfirmation()).to(ex).with("mt.confirmation"); }
        @Bean Binding b3(DirectExchange ex) { return BindingBuilder.bind(mtBulk()).to(ex).with("mt.bulk"); }

        private Queue mtQueue(String p) {
            return QueueBuilder.durable("vas.mt." + p).quorum().deadLetterExchange("").deadLetterRoutingKey("vas.mt.dlq").build();
        }
    }

    @Component
    @ConditionalOnProperty(name = "vas.queue", havingValue = "rabbit")
    static class Consumers {
        private final MtDispatcher dispatcher;

        Consumers(MtDispatcher d) { this.dispatcher = d; }

        @RabbitListener(queues = "vas.mt.transactional", concurrency = "${vas.mt-consumers.transactional:4-8}")
        void tx(String id) { dispatcher.dispatch(id); }

        @RabbitListener(queues = "vas.mt.confirmation", concurrency = "${vas.mt-consumers.confirmation:2-4}")
        void conf(String id) { dispatcher.dispatch(id); }

        @RabbitListener(queues = "vas.mt.bulk", concurrency = "${vas.mt-consumers.bulk:1-2}")
        void bulk(String id) { dispatcher.dispatch(id); }
    }
}
