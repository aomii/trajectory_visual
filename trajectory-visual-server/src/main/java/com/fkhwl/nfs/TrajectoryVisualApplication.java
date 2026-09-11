package com.fkhwl.nfs;

import com.fkhwl.nfs.config.ExperimentProperties;
import com.fkhwl.nfs.config.VisualProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * trajectory-visual-server 启动类。
 *
 * <p>职责：论文第 5.3 节"可视化子系统"的在线化形态——对外提供运单/轨迹/压缩/时间窗检索
 * 与第 6 章评估结果查询接口；压缩分块只写 MongoDB，第 6 章实验结果写 MySQL。
 *
 * <p>启动（local profile，配置见 application-local.yml）：
 * <pre>
 *   mvn spring-boot:run -Dspring-boot.run.profiles=local
 * </pre>
 * 或直接运行本类并激活 local profile。
 */
@SpringBootApplication
@EnableAsync
@EnableConfigurationProperties({VisualProperties.class, ExperimentProperties.class})
@MapperScan("com.fkhwl.nfs.biz.mapper")
public class TrajectoryVisualApplication {

    public static void main(String[] args) {
        SpringApplication.run(TrajectoryVisualApplication.class, args);
    }
}
