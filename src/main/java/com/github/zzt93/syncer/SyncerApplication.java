package com.github.zzt93.syncer;

import com.github.zzt93.syncer.common.SyncData;
import com.github.zzt93.syncer.config.pipeline.PipelineConfig;
import com.github.zzt93.syncer.config.syncer.SyncerConfig;
import com.github.zzt93.syncer.filter.FilterStarter;
import com.github.zzt93.syncer.input.InputStarter;
import com.github.zzt93.syncer.output.OutputStarter;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.Banner;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;


@SpringBootApplication(exclude = {DataSourceAutoConfiguration.class})
public class SyncerApplication implements CommandLineRunner {

  @Autowired
  private PipelineConfig pipelineConfig;
  @Autowired
  private SyncerConfig syncerConfig;

  public static void main(String[] args) {
    SpringApplication application = new SpringApplication(SyncerApplication.class);
    application.setWebApplicationType(WebApplicationType.NONE);
    application.setBannerMode(Banner.Mode.OFF);
    application.run(args);
  }

  @Override
  public void run(String... strings) throws Exception {
    BlockingDeque<SyncData> inputFilter = new LinkedBlockingDeque<>();
    BlockingDeque<SyncData> filterOutput = new LinkedBlockingDeque<>();
    InputStarter.getInstance(pipelineConfig.getInput(), syncerConfig.getInput(), inputFilter).start();
    FilterStarter.getInstance(pipelineConfig.getFilter(), syncerConfig.getFilter(), inputFilter, filterOutput).start();
    OutputStarter.getInstance(pipelineConfig.getOutput(), syncerConfig.getOutput(), filterOutput).start();
  }

}
