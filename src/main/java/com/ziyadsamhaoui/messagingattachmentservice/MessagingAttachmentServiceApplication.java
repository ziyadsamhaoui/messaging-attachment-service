package com.ziyadsamhaoui.messagingattachmentservice;

import com.ziyadsamhaoui.messagingattachmentservice.config.AttachmentProperties;
import com.ziyadsamhaoui.messagingattachmentservice.config.ChatServiceProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({AttachmentProperties.class, ChatServiceProperties.class})
public class MessagingAttachmentServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(MessagingAttachmentServiceApplication.class, args);
    }
}
