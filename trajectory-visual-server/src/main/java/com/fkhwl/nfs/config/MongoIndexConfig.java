package com.fkhwl.nfs.config;

import com.fkhwl.nfs.biz.entity.mongo.TrajectoryChunkDoc;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;

/**
 * MongoDB 索引初始化（集合 trajectory_chunk）。
 * 规格书 §7 建议索引：waybillId+chunkIndex、waybillNo、waybillId+startTime+endTime(时间范围)。
 * 启动时幂等创建，不存在才建。
 */
@Configuration
public class MongoIndexConfig {

    private static final String COLL = "trajectory_chunk";

    @Bean
    public ApplicationRunner createChunkIndexes(MongoTemplate mongo) {
        return args -> {
            mongo.indexOps(COLL).ensureIndex(
                    new Index().on("waybillId", Sort.Direction.ASC)
                            .on("chunkIndex", Sort.Direction.ASC).named("idx_waybill_chunk"));
            mongo.indexOps(COLL).ensureIndex(
                    new Index().on("waybillNo", Sort.Direction.ASC).named("idx_waybill_no"));
            mongo.indexOps(COLL).ensureIndex(
                    new Index().on("waybillId", Sort.Direction.ASC)
                            .on("startTime", Sort.Direction.ASC)
                            .on("endTime", Sort.Direction.ASC).named("idx_time_range"));
        };
    }
}
