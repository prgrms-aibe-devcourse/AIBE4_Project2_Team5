package kr.eolmago.global.config;

import kr.eolmago.global.config.properties.AuctionRuntimeProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
@EnableConfigurationProperties(AuctionRuntimeProperties.class)
public class BidTransactionTemplateConfig {

    @Bean("bidWriteTransactionTemplate")
    public TransactionTemplate bidWriteTransactionTemplate(
        PlatformTransactionManager transactionManager,
        AuctionRuntimeProperties auctionRuntimeProperties
    ) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);

        // 입찰 쓰기 구간은 항상 독립 트랜잭션으로 실행
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        template.setReadOnly(false);
        template.setTimeout(auctionRuntimeProperties.getBid().getTransactionTimeoutSec());
        template.setName("auction-bid-write-tx");

        return template;
    }
}
