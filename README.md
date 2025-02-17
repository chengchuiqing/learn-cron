## 0x01 前言

在 SpringBoot 项目中，我们可以通过`@EnableScheduling`注解开启调度任务支持，并通过`@Scheduled`注解快速地建立一系列定时任务。

`@Scheduled`支持下面三种配置执行时间的方式：

- cron(expression)：根据`Cron`表达式来执行。
- fixedDelay(period)：固定间隔时间执行，无论任务执行长短，两次任务执行的间隔总是相同的。
- fixedRate(period)：固定频率执行，从任务启动之后，总是在固定的时刻执行，如果因为执行时间过长，造成错过某个时刻的执行（晚点），则任务会被立刻执行。

最常用的应该是第一种方式，基于`Cron`表达式的执行模式，因其相对来说更加灵活。

## 0x02 可变与不可变

默认情况下，`@Scheduled`注解标记的定时任务方法在初始化之后，是不会再发生变化的。Spring 在初始化 bean 后，通过后处理器拦截所有带有`@Scheduled`注解的方法，并解析相应的的注解参数，放入相应的定时任务列表等待后续统一执行处理。到定时任务真正启动之前，我们都有机会更改任务的执行周期等参数。换言之，我们既可以通过`application.properties`配置文件配合`@Value`注解的方式指定任务的`Cron`表达式，亦可以通过`CronTrigger`从数据库或者其他任意存储中间件中加载并注册定时任务。这是 Spring 提供给我们的可变的部分。

但是我们往往要得更多。能否在定时任务已经在执行过的情况下，去动态更改`Cron`表达式，甚至禁用某个定时任务呢？很遗憾，默认情况下，这是做不到的，任务一旦被注册和执行，用于注册的参数便被固定下来，这是不可变的部分。

## 0x03 创造与毁灭

既然创造之后不可变，那就毁灭之后再重建吧。于是乎，我们的思路便是，在注册期间保留任务的关键信息，并通过另一个定时任务检查配置是否发生变化，如果有变化，就把“前任”干掉，取而代之。如果没有变化，就保持原样。

先对任务做个简单的抽象，方便统一的识别和管理：

```java
public interface IPollableService {
    /**
     * 执行方法
     */
    void poll();

    /**
     * 获取周期表达式
     *
     * @return CronExpression
     */
    default String getCronExpression() {
        return null;
    }

    /**
     * 获取任务名称
     *
     * @return 任务名称
     */
    default String getTaskName() {
        return this.getClass().getSimpleName();
    }
}
```

最重要的便是`getCronExpression()`方法，每个定时服务实现可以自己控制自己的表达式，变与不变，自己说了算。至于从何处获取，怎么获取，请诸君自行发挥了。接下来，就是实现任务的动态注册：

```java
@Configuration
@EnableAsync
@EnableScheduling
public class SchedulingConfiguration implements SchedulingConfigurer, ApplicationContextAware {
    private static final Logger log = LoggerFactory.getLogger(SchedulingConfiguration.class);
    private static ApplicationContext appCtx;
    private final ConcurrentMap<String, ScheduledTask> scheduledTaskHolder = new ConcurrentHashMap<>(16);
    private final ConcurrentMap<String, String> cronExpressionHolder = new ConcurrentHashMap<>(16);
    private ScheduledTaskRegistrar taskRegistrar;

    public static synchronized void setAppCtx(ApplicationContext appCtx) {
        SchedulingConfiguration.appCtx = appCtx;
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        setAppCtx(applicationContext);
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        this.taskRegistrar = taskRegistrar;
    }

    /**
     * 刷新定时任务表达式
     */
    public void refresh() {
        Map<String, IPollableService> beanMap = appCtx.getBeansOfType(IPollableService.class);
        if (beanMap.isEmpty() || taskRegistrar == null) {
            return;
        }
        beanMap.forEach((beanName, task) -> {
            String expression = task.getCronExpression();
            String taskName = task.getTaskName();
            if (null == expression) {
                log.warn("定时任务[{}]的任务表达式未配置或配置错误，请检查配置", taskName);
                return;
            }
            // 如果策略执行时间发生了变化，则取消当前策略的任务，并重新注册任务
            boolean unmodified = scheduledTaskHolder.containsKey(beanName) && cronExpressionHolder.get(beanName).equals(expression);
            if (unmodified) {
                log.info("定时任务[{}]的任务表达式未发生变化，无需刷新", taskName);
                return;
            }
            Optional.ofNullable(scheduledTaskHolder.remove(beanName)).ifPresent(existTask -> {
                existTask.cancel();
                cronExpressionHolder.remove(beanName);
            });
            if (ScheduledTaskRegistrar.CRON_DISABLED.equals(expression)) {
                log.warn("定时任务[{}]的任务表达式配置为禁用，将被不会被调度执行", taskName);
                return;
            }
            CronTask cronTask = new CronTask(task::poll, expression);
            ScheduledTask scheduledTask = taskRegistrar.scheduleCronTask(cronTask);
            if (scheduledTask != null) {
                log.info("定时任务[{}]已加载，当前任务表达式为[{}]", taskName, expression);
                scheduledTaskHolder.put(beanName, scheduledTask);
                cronExpressionHolder.put(beanName, expression);
            }
        });
    }
}
```

重点是保存`ScheduledTask`对象的引用，它是控制任务启停的关键。而表达式“-”则作为一个特殊的标记，用于禁用某个定时任务。当然，禁用后的任务通过重新赋予新的 Cron 表达式，是可以“复活”的。完成了上面这些，我们还需要一个定时任务来动态监控和刷新定时任务配置：

```java
@Component
public class CronTaskLoader implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(CronTaskLoader.class);
    private final SchedulingConfiguration schedulingConfiguration;
    private final AtomicBoolean appStarted = new AtomicBoolean(false);
    private final AtomicBoolean initializing = new AtomicBoolean(false);

    public CronTaskLoader(SchedulingConfiguration schedulingConfiguration) {
        this.schedulingConfiguration = schedulingConfiguration;
    }

    /**
     * 定时任务配置刷新
     */
    @Scheduled(fixedDelay = 5000)
    public void cronTaskConfigRefresh() {
        if (appStarted.get() && initializing.compareAndSet(false, true)) {
            log.info("定时调度任务动态加载开始>>>>>>");
            try {
                schedulingConfiguration.refresh();
            } finally {
                initializing.set(false);
            }
            log.info("定时调度任务动态加载结束<<<<<<");
        }
    }

    @Override
    public void run(ApplicationArguments args) {
        if (appStarted.compareAndSet(false, true)) {
            cronTaskConfigRefresh();
        }
    }
}
```

当然，也可以把这部分代码直接整合到`SchedulingConfiguration`中，但是为了方便扩展，这里还是将执行与触发分离了。毕竟除了通过定时任务触发刷新，还可以在界面上通过按钮手动触发刷新，或者通过消息机制回调刷新。这一部分就请大家根据实际业务情况来自由发挥了。

## 0x04 验证

我们创建一个原型工程和三个简单的定时任务来验证下，第一个任务是执行周期固定的任务，假设它的`Cron`表达式永远不会发生变化，像这样：

```java
@Service
public class CronTaskBar implements IPollableService {
    @Override
    public void poll() {
        System.out.println("Say Bar");
    }

    @Override
    public String getCronExpression() {
        return "0/1 * * * * ?";
    }
}
```

第二个任务是一个经常更换执行周期的任务，我们用一个随机数发生器来模拟它的善变：

```java
@Service
public class CronTaskFoo implements IPollableService {
    private static final Random random = new SecureRandom();

    @Override
    public void poll() {
        System.out.println("Say Foo");
    }

    @Override
    public String getCronExpression() {
        return "0/" + (random.nextInt(9) + 1) + " * * * * ?";
    }
}
```

第三个任务就厉害了，它仿佛就像一个电灯的开关，在启用和禁用中反复横跳：

```java
@Service
public class CronTaskUnavailable implements IPollableService {
    private String cronExpression = "-";
    private static final Map<String, String> map = new HashMap<>();

    static {
        map.put("-", "0/1 * * * * ?");
        map.put("0/1 * * * * ?", "-");
    }

    @Override
    public void poll() {
        System.out.println("Say Unavailable");
    }

    @Override
    public String getCronExpression() {
        return (cronExpression = map.get(cronExpression));
    }
}
```

如果上面的步骤都做对了，日志里应该能看到类似这样的输出：

```tex
定时调度任务动态加载开始>>>>>>
定时任务[CronTaskBar]的任务表达式未发生变化，无需刷新
定时任务[CronTaskFoo]已加载，当前任务表达式为[0/6 * * * * ?]
定时任务[CronTaskUnavailable]的任务表达式配置为禁用，将被不会被调度执行
定时调度任务动态加载结束<<<<<<
Say Bar
Say Bar
Say Foo
Say Bar
Say Bar
Say Bar
定时调度任务动态加载开始>>>>>>
定时任务[CronTaskBar]的任务表达式未发生变化，无需刷新
定时任务[CronTaskFoo]已加载，当前任务表达式为[0/3 * * * * ?]
定时任务[CronTaskUnavailable]已加载，当前任务表达式为[0/1 * * * * ?]
定时调度任务动态加载结束<<<<<<
Say Unavailable
Say Bar
Say Unavailable
Say Bar
Say Foo
Say Unavailable
Say Bar
Say Unavailable
Say Bar
Say Unavailable
Say Bar
```

## 0x05 小结

我们在上文通过定时刷新和重建任务的方式来实现了动态更改`Cron`表达式的需求，能够满足大部分的项目场景，而且没有引入`quartzs`等额外的中间件，可以说是十分的轻量和优雅了。



>https://www.cnblogs.com/mylibs/p/dynamic-change-of-cron-expression.html































































# -- END --