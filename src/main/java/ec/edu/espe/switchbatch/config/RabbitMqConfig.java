package ec.edu.espe.switchbatch.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMqConfig {

    @Bean
    public Queue paymentLinesQueue(FileReceptionProperties properties) {
        return new Queue(properties.getRabbitQueue(), true);
    }

    @Bean
    public DirectExchange paymentExchange(FileReceptionProperties properties) {
        return new DirectExchange(properties.getRabbitExchange(), true, false);
    }

    @Bean
    public Binding paymentLinesBinding(Queue paymentLinesQueue, DirectExchange paymentExchange,
                                        FileReceptionProperties properties) {
        return BindingBuilder.bind(paymentLinesQueue).to(paymentExchange).with(properties.getRabbitRoutingKey());
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new JacksonJsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, MessageConverter jsonMessageConverter) {
        RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
        rabbitTemplate.setMessageConverter(jsonMessageConverter);
        return rabbitTemplate;
    }
}
