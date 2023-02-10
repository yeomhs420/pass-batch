package com.yeom.pass.job.pass;
import com.yeom.pass.repository.booking.BookingEntity;
import com.yeom.pass.repository.booking.BookingRepository;
import com.yeom.pass.repository.booking.BookingStatus;
import com.yeom.pass.repository.pass.PassEntity;
import com.yeom.pass.repository.pass.PassRepository;
import com.yeom.pass.repository.statistics.StatisticsEntity;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.JobBuilderFactory;
import org.springframework.batch.core.configuration.annotation.JobScope;
import org.springframework.batch.core.configuration.annotation.StepBuilderFactory;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.batch.integration.async.AsyncItemProcessor;
import org.springframework.batch.integration.async.AsyncItemWriter;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.database.JpaCursorItemReader;
import org.springframework.batch.item.database.builder.JpaCursorItemReaderBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.integration.support.json.JsonOutboundMessageMapper;

import javax.persistence.EntityManagerFactory;
import java.awt.print.Book;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.Future;

@Configuration
public class UsePassesJobConfig {   // 수업 종료 후 이용권 차감

    private final int CHUNK_SIZE = 10;

    // @EnableBatchProcessing로 인해 Bean으로 제공된 JobBuilderFactory, StepBuilderFactory
    private final JobBuilderFactory jobBuilderFactory;
    private final StepBuilderFactory stepBuilderFactory;
    private final EntityManagerFactory entityManagerFactory;
    private final PassRepository passRepository;
    private final BookingRepository bookingRepository;

    public UsePassesJobConfig(JobBuilderFactory jobBuilderFactory, StepBuilderFactory stepBuilderFactory, EntityManagerFactory entityManagerFactory, PassRepository passRepository, BookingRepository bookingRepository) {
        this.jobBuilderFactory = jobBuilderFactory;
        this.stepBuilderFactory = stepBuilderFactory;
        this.entityManagerFactory = entityManagerFactory;
        this.passRepository = passRepository;
        this.bookingRepository = bookingRepository;
    }

    @Bean
    public Job usePassesJob() {
        return this.jobBuilderFactory.get("usePassesJob")
                //.incrementer(new RunIdIncrementer())
                .start(usePassesStep())
                .build();

    }

    @Bean
    public Step usePassesStep() {
        return this.stepBuilderFactory.get("usePassesStep")
                .<BookingEntity, Future<BookingEntity>>chunk(CHUNK_SIZE)
                .reader(usePassesItemReader(null))
                .processor(usePassesAsyncItemProcessor())
                .writer(usePassesAsyncItemWriter())
                .allowStartIfComplete(true)// 완료된 스텝 재실행하기
                .build();

    }

//    @Bean
//    private Step cancelPassStep() {
//        return this.stepBuilderFactory.get("cancelPassStep")
//                .<BookingEntity, BookingEntity>chunk(CHUNK_SIZE)
//                .reader()
//                .processor()
//                .writer()
//                .build();
//    }

    @Bean
    @StepScope
    public JpaCursorItemReader<BookingEntity> usePassesItemReader(@Value("#{jobParameters[userId]}") String userId) {    //@Value("#{jobParameters[userId]}") String userId

        return new JpaCursorItemReaderBuilder<BookingEntity>()
                .name("usePassesItemReader")
                .entityManagerFactory(entityManagerFactory)
                .queryString("select b from BookingEntity b join fetch b.passEntity where b.status = :status and b.usedPass = false and b.endedAt < :endedAt and b.userId = :userId")
                .parameterValues(Map.of("status", BookingStatus.COMPLETED, "endedAt", LocalDateTime.now(), "userId", userId))
                .build();
    }

    @Bean
    public AsyncItemProcessor<BookingEntity, BookingEntity> usePassesAsyncItemProcessor(){  // ItemProcessor 에 병목현상이 있는 경우 성능 up
        AsyncItemProcessor<BookingEntity, BookingEntity> asyncItemProcessor = new AsyncItemProcessor<>();
        asyncItemProcessor.setDelegate(usePassesItemProcessor());   // usePassesItemProcessor 가 새로운 Thread 위에서 동작 = 멀티쓰레드로 동작
        asyncItemProcessor.setTaskExecutor(new SimpleAsyncTaskExecutor());  // Thread 를 생성하여 processor 에 실제 작업 위임
        return asyncItemProcessor;
    } // -> ItemProcessor 에게 실행을 위임하고 결과를 Future 에 저장

    @Bean
    public ItemProcessor<BookingEntity, BookingEntity> usePassesItemProcessor(){
        return bookingEntity -> {
            PassEntity passEntity = bookingEntity.getPassEntity();
            passEntity.setRemainingCount(passEntity.getRemainingCount() - 1);
            bookingEntity.setPassEntity(passEntity);

            bookingEntity.setUsedPass(true);
            return bookingEntity;
        };
    }

    @Bean
    public AsyncItemWriter<BookingEntity> usePassesAsyncItemWriter() {  // AsyncItemWriter : 실행 결과 값들(List<Future)을 모두 받아오기 까지 대기
        AsyncItemWriter<BookingEntity> asyncItemWriter = new AsyncItemWriter<>();
        asyncItemWriter.setDelegate(usePassesItemWriter());
        return asyncItemWriter;

    }

    @Bean
    public ItemWriter<BookingEntity> usePassesItemWriter(){
        return bookEntities -> {
            for(BookingEntity bookingEntity : bookEntities){
                int updatedCount = passRepository.updateRemainingCount(bookingEntity.getPassSeq(), bookingEntity.getPassEntity().getRemainingCount());
                if(updatedCount > 0)
                    bookingRepository.updateUsedPass(bookingEntity.getPassSeq(), bookingEntity.isUsedPass());
            }
        };
    }
}
