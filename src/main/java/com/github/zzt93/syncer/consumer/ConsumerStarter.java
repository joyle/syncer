package com.github.zzt93.syncer.consumer;

import com.github.zzt93.syncer.ShutDownCenter;
import com.github.zzt93.syncer.Starter;
import com.github.zzt93.syncer.common.data.SyncData;
import com.github.zzt93.syncer.common.data.SyncInitMeta;
import com.github.zzt93.syncer.common.util.NamedThreadFactory;
import com.github.zzt93.syncer.config.pipeline.ConsumerConfig;
import com.github.zzt93.syncer.config.pipeline.common.MasterSource;
import com.github.zzt93.syncer.config.pipeline.filter.FilterConfig;
import com.github.zzt93.syncer.config.pipeline.input.PipelineInput;
import com.github.zzt93.syncer.config.pipeline.input.SyncMeta;
import com.github.zzt93.syncer.config.pipeline.output.PipelineOutput;
import com.github.zzt93.syncer.config.syncer.SyncerAck;
import com.github.zzt93.syncer.config.syncer.SyncerConfig;
import com.github.zzt93.syncer.config.syncer.SyncerFilter;
import com.github.zzt93.syncer.config.syncer.SyncerInput;
import com.github.zzt93.syncer.config.syncer.SyncerOutput;
import com.github.zzt93.syncer.consumer.ack.Ack;
import com.github.zzt93.syncer.consumer.filter.ExprFilter;
import com.github.zzt93.syncer.consumer.filter.FilterJob;
import com.github.zzt93.syncer.consumer.filter.impl.ForeachFilter;
import com.github.zzt93.syncer.consumer.filter.impl.If;
import com.github.zzt93.syncer.consumer.filter.impl.Statement;
import com.github.zzt93.syncer.consumer.filter.impl.Switch;
import com.github.zzt93.syncer.consumer.input.EventScheduler;
import com.github.zzt93.syncer.consumer.input.LocalConsumerSource;
import com.github.zzt93.syncer.consumer.input.PositionFlusher;
import com.github.zzt93.syncer.consumer.input.Registrant;
import com.github.zzt93.syncer.consumer.input.SchedulerBuilder;
import com.github.zzt93.syncer.consumer.output.OutputStarter;
import com.github.zzt93.syncer.consumer.output.channel.OutputChannel;
import com.github.zzt93.syncer.health.Health;
import com.github.zzt93.syncer.health.SyncerHealth;
import com.github.zzt93.syncer.producer.input.mysql.connect.BinlogInfo;
import com.github.zzt93.syncer.producer.register.ConsumerRegistry;
import com.google.common.base.Preconditions;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.expression.spel.standard.SpelExpressionParser;

/**
 * Abstraction of a consumer which is initiated by a pipeline config file
 * @author zzt
 */
public class ConsumerStarter implements Starter<List<FilterConfig>, List<ExprFilter>> {

  private final Logger logger = LoggerFactory.getLogger(ConsumerStarter.class);
  private ExecutorService filterOutputService;
  private FilterJob[] filterJobs;
  private int worker;
  private SyncerAck ackConfig;
  private Registrant registrant;
  private Ack ack;
  private final String id;
  private final List<OutputChannel> outputChannels;
  private OutputStarter outputStarter;

  public ConsumerStarter(ConsumerConfig pipeline, SyncerConfig syncer,
                         ConsumerRegistry consumerRegistry) throws Exception {

    id = pipeline.getConsumerId();
    HashMap<String, SyncInitMeta> id2SyncInitMeta = initAckModule(id, pipeline.getInput(),
        syncer.getInput(), syncer.getAck());

    outputChannels = initBatchOutputModule(id, pipeline.getOutput(), syncer.getOutput());

    SchedulerBuilder schedulerBuilder = new SchedulerBuilder();
    initFilterModule(ack, syncer.getFilter(), pipeline.getFilter(), schedulerBuilder, outputChannels);

    initRegistrant(id, consumerRegistry, schedulerBuilder, pipeline.getInput(), id2SyncInitMeta);
  }

  private HashMap<String, SyncInitMeta> initAckModule(String consumerId,
      PipelineInput pipelineInput,
      SyncerInput input, SyncerAck ackConfig) {
    Set<MasterSource> masterSet = pipelineInput.getMasterSet();
    HashMap<String, SyncInitMeta> id2SyncInitMeta = new HashMap<>();
    this.ack = Ack.build(consumerId, input.getInputMeta(), masterSet, id2SyncInitMeta);
    this.ackConfig = ackConfig;
    return id2SyncInitMeta;
  }

  private List<OutputChannel> initBatchOutputModule(String id, PipelineOutput pipeline,
      SyncerOutput syncer) throws Exception {
    outputStarter = new OutputStarter(id, pipeline, syncer, ack);
    return outputStarter.getOutputChannels();
  }

  private void initFilterModule(Ack ack, SyncerFilter module, List<FilterConfig> filters,
      SchedulerBuilder schedulerBuilder, List<OutputChannel> outputChannels) {
    Preconditions
        .checkArgument(module.getWorker() <= Runtime.getRuntime().availableProcessors() * 3,
            "Too many worker thread");
    Preconditions.checkArgument(module.getWorker() > 0, "Invalid worker thread number config");
    filterOutputService = Executors
        .newFixedThreadPool(module.getWorker(), new NamedThreadFactory("syncer-filter-output"));

    List<ExprFilter> exprFilters = fromPipelineConfig(filters);
    worker = module.getWorker();
    filterJobs = new FilterJob[worker];
    BlockingDeque<SyncData>[] deques = new BlockingDeque[worker];
    for (int i = 0; i < worker; i++) {
      deques[i] = new LinkedBlockingDeque<>();
      // TODO 18/8/15 test remove in foreach & opt
      filterJobs[i] = new FilterJob(id, ack, deques[i], new CopyOnWriteArrayList<>(outputChannels),
          exprFilters);
    }
    schedulerBuilder.setDeques(deques);
  }

  private void initRegistrant(String consumerId, ConsumerRegistry consumerRegistry,
      SchedulerBuilder schedulerBuilder,
      PipelineInput input,
      HashMap<String, SyncInitMeta> id2SyncInitMeta) {
    registrant = new Registrant(consumerRegistry);
    for (MasterSource masterSource : input.getMasterSet()) {
      String identifier = masterSource.getConnection().connectionIdentifier();
      SyncInitMeta syncInitMeta = id2SyncInitMeta.get(identifier);
      if (masterSource.hasSyncMeta()) {
        SyncMeta syncMeta = masterSource.getSyncMeta();
        logger.warn("Override syncer remembered position with config in file {}, watch out",
            syncMeta);
        syncInitMeta = BinlogInfo.withFilenameCheck(syncMeta.getBinlogFilename(), syncMeta.getBinlogPosition());
      }
      EventScheduler scheduler = schedulerBuilder.setSchedulerType(masterSource.getScheduler())
          .build();
      LocalConsumerSource localInputSource = LocalConsumerSource
          .inputSource(consumerId, masterSource, syncInitMeta, scheduler);
      registrant.addDatasource(localInputSource);
    }
  }

  @Override
  public List<ExprFilter> fromPipelineConfig(List<FilterConfig> filters) {
    SpelExpressionParser parser = new SpelExpressionParser();
    List<ExprFilter> res = new ArrayList<>();
    for (FilterConfig filter : filters) {
      switch (filter.getType()) {
        case SWITCH:
          res.add(new Switch(parser, filter.getSwitcher()));
          break;
        case STATEMENT:
          res.add(new Statement(parser, filter.getStatement()));
          break;
        case FOREACH:
          res.add(new ForeachFilter(parser, filter.getForeach()));
          break;
        case IF:
          res.add(new If(parser, filter.getIf()));
          break;
        default:
          throw new IllegalArgumentException("Unknown filter type");
      }
    }
    return res;
  }

  public Starter start() throws InterruptedException, IOException {
    startAck();
    for (int i = 0; i < worker; i++) {
      filterOutputService.submit(filterJobs[i]);
    }
    return this;
  }

  public void close() throws InterruptedException {
    // close output channel first
    outputStarter.close();
    // stop filter-output threads
    filterOutputService.shutdownNow();
    while (!filterOutputService.awaitTermination(ShutDownCenter.SHUTDOWN_TIMEOUT, TimeUnit.SECONDS)) {
      logger.error("[Shutting down] consumer: {}", id);
    }
  }

  @Override
  public void registerToHealthCenter() {
    for (OutputChannel outputChannel : outputChannels) {
      SyncerHealth.consumer(id, outputChannel.id(), Health.green());
    }
  }

  private void startAck() throws IOException {
    ScheduledExecutorService scheduled = Executors
        .newScheduledThreadPool(1, new NamedThreadFactory("syncer-ack"));
    if (registrant.register()) {
      scheduled.scheduleAtFixedRate(new PositionFlusher(ack), 0, ackConfig.getFlushPeriod(),
          ackConfig.getUnit());
    } else {
      logger.warn("Fail to register");
    }
  }
}
