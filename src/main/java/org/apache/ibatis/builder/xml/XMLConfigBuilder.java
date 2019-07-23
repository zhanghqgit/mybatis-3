/**
 *    Copyright 2009-2017 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.apache.ibatis.builder.xml;

import java.io.InputStream;
import java.io.Reader;
import java.util.Properties;
import javax.sql.DataSource;

import org.apache.ibatis.binding.MapperMethod;
import org.apache.ibatis.binding.MapperProxyFactory;
import org.apache.ibatis.binding.MapperRegistry;
import org.apache.ibatis.builder.BaseBuilder;
import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.builder.annotation.MapperAnnotationBuilder;
import org.apache.ibatis.datasource.DataSourceFactory;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.executor.loader.ProxyFactory;
import org.apache.ibatis.io.ClassLoaderWrapper;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.io.VFS;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.mapping.DatabaseIdProvider;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.parsing.XPathParser;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.reflection.DefaultReflectorFactory;
import org.apache.ibatis.reflection.MetaClass;
import org.apache.ibatis.reflection.Reflector;
import org.apache.ibatis.reflection.ReflectorFactory;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.reflection.wrapper.ObjectWrapperFactory;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.TransactionFactory;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeHandler;

/**
 * 主要用来构造 {@link Configuration} ,根据传入的输入流及相关配置,解析出 {@link Configuration}
 * 此处来定义解析配置文件中的哪些节点
 *
 * 无需关系文档如何解析,只是告诉需要解析文档中的哪些节点,具体如何解析无需关系,已全部底层封装
 *
 * @author Clinton Begin
 * @author Kazuki Shimizu
 */
public class XMLConfigBuilder extends BaseBuilder {

  private boolean parsed;
  private final XPathParser parser;
  /**
   * 默认环境
   */
  private String environment;
  private final ReflectorFactory localReflectorFactory = new DefaultReflectorFactory();

  public XMLConfigBuilder(Reader reader) {
    this(reader, null, null);
  }

  public XMLConfigBuilder(Reader reader, String environment) {
    this(reader, environment, null);
  }

  public XMLConfigBuilder(Reader reader, String environment, Properties props) {
    this(new XPathParser(reader, true, props, new XMLMapperEntityResolver()), environment, props);
  }

  public XMLConfigBuilder(InputStream inputStream) {
    this(inputStream, null, null);
  }

  public XMLConfigBuilder(InputStream inputStream, String environment) {
    this(inputStream, environment, null);
  }

  public XMLConfigBuilder(InputStream inputStream, String environment, Properties props) {
    this(new XPathParser(inputStream, true, props, new XMLMapperEntityResolver()), environment, props);
  }

  private XMLConfigBuilder(XPathParser parser, String environment, Properties props) {
    super(new Configuration());
    ErrorContext.instance().resource("SQL Mapper Configuration");
    this.configuration.setVariables(props);
    this.parsed = false;
    this.environment = environment;
    this.parser = parser;
  }

  public Configuration parse() {
    if (parsed) {
      throw new BuilderException("Each XMLConfigBuilder can only be used once.");
    }
    parsed = true;
    parseConfiguration(parser.evalNode("/configuration"));
    return configuration;
  }

  /**
   * mybatis-config.xml
   * <configuration>
   *     <properties>
   *         <property></property>
   *     </properties>
   *     <settings>
   *          <setting></setting>
   *     </settings>
   *     <typeAliases>
   *         <typeAlias></typeAlias>
   *     </typeAliases>
   *     <typeHandlers>
   *         <typeHandler></typeHandler>
   *     </typeHandlers>
   *     <objectFactory></objectFactory>
   *     <objectWrapperFactory></objectWrapperFactory>
   *     <reflectorFactory></reflectorFactory>
   *     <plugins>
   *         <plugin></plugin>
   *     </plugins>
   *     <environments>
   *         <environment>
   *             <transactionManager></transactionManager>
   *             <dataSource></dataSource>
   *         </environment>
   *     </environments>
   *     <databaseIdProvider>
   *         <property></property>
   *     </databaseIdProvider>
   *     <mappers>
   *         <mapper></mapper>
   *         or
   *         <package></package>
   *     </mappers>
   * </configuration>
   * @param root
   */
  private void parseConfiguration(XNode root) {
    try {
      //issue #117 read properties first
      /**
       * properties节点中尽量不要使用占位符,其无法引用自身property节点中设置的属性配置，仅能获取到java中设置的属性配置
       * 此处配置的属性值可被整个配置文件使用
       */
      propertiesElement(root.evalNode("properties"));
      /**
       * 获取所有的配置，应用于org.apache.ibatis.session.Configuration的属性配置
       */
      Properties settings = settingsAsProperties(root.evalNode("settings"));
      /**
       * vfsImpl 配置
       */
      loadCustomVfs(settings);
      /**
       * 类型别名 java内置类型的别名 基本类型 + 包装类型 + 内置类型数组 + String
       */
      typeAliasesElement(root.evalNode("typeAliases"));
      /**
       * 插件扩展
       */
      pluginElement(root.evalNode("plugins"));
      /**
       * 设置自定义的实体构造器，用于返回值初始化，即使用构造器初始化类
       */
      objectFactoryElement(root.evalNode("objectFactory"));
      /**
       * 设置实体的属性get set等包装类,用于将数据库记录字段映射
       */
      objectWrapperFactoryElement(root.evalNode("objectWrapperFactory"));
      /**
       * 类的源信息反射器工厂,包装类
       */
      reflectorFactoryElement(root.evalNode("reflectorFactory"));
      /**
       * {@link Configuration}配置设置
       */
      settingsElement(settings);
      // read it after objectFactory and objectWrapperFactory issue #631
      /**
       * 环境
       * @see org.apache.ibatis.transaction.TransactionFactory
       * @see DataSourceFactory
       */
      environmentsElement(root.evalNode("environments"));
      /**
       * 数据库厂商特定 org.apache.ibatis.mapping.DatabaseIdProvider
       * 与 mapper xml中的 select insert update delete 标签相关，仅加载指定的dataBaseId的标签
       */
      databaseIdProviderElement(root.evalNode("databaseIdProvider"));
      /**
       * 类型处理器
       */
      typeHandlerElement(root.evalNode("typeHandlers"));
      /**
       * mapper 管理 主要为 sql xml文件、mapper interface 及注解SQL等
       */
      mapperElement(root.evalNode("mappers"));
    } catch (Exception e) {
      throw new BuilderException("Error parsing SQL Mapper Configuration. Cause: " + e, e);
    }
  }

  /**
   * 关键类
   * @see org.apache.ibatis.reflection.MetaClass
   * @see org.apache.ibatis.reflection.property.PropertyTokenizer
   * @see org.apache.ibatis.reflection.DefaultReflectorFactory
   * @see org.apache.ibatis.reflection.Reflector
   *
   * 获取所有的setting,并校验名字的正确性,支持递归，意即多层级属性设置,以 . 为分隔，可支持 [] 代表集合，自动去除[]
   * 所有的属性均需要规范的get\set方法，大小写敏感
   * @param context
   * @return
   */
  private Properties settingsAsProperties(XNode context) {
    if (context == null) {
      return new Properties();
    }
    Properties props = context.getChildrenAsProperties();
    // Check that all settings are known to the configuration class
    MetaClass metaConfig = MetaClass.forClass(Configuration.class, localReflectorFactory);
    for (Object key : props.keySet()) {
      if (!metaConfig.hasSetter(String.valueOf(key))) {
        throw new BuilderException("The setting " + key + " is not known.  Make sure you spelled it correctly (case sensitive).");
      }
    }
    return props;
  }

  /**
   * 多个实现使用逗号分隔
   * 用于在AS中加载资源
   * @param props
   * @throws ClassNotFoundException
   */
  private void loadCustomVfs(Properties props) throws ClassNotFoundException {
    String value = props.getProperty("vfsImpl");
    if (value != null) {
      String[] clazzes = value.split(",");
      for (String clazz : clazzes) {
        if (!clazz.isEmpty()) {
          @SuppressWarnings("unchecked")
          Class<? extends VFS> vfsImpl = (Class<? extends VFS>)Resources.classForName(clazz);
          configuration.setVfsImpl(vfsImpl);
        }
      }
    }
  }

  /**
   * 关键类
   * org.apache.ibatis.io.ResolverUtil
   * org.apache.ibatis.io.VFS
   * @param parent
   */
  private void typeAliasesElement(XNode parent) {
    if (parent != null) {
      for (XNode child : parent.getChildren()) {
        if ("package".equals(child.getName())) {
          //按包名注册类型  级联获取指定路径下的所有类 排除匿名类、成员类、接口、package-info.java
          //不可重复注册
          String typeAliasPackage = child.getStringAttribute("name");
          configuration.getTypeAliasRegistry().registerAliases(typeAliasPackage);
        } else {
          //单个类注册，可以不指定别名
          /**
           * xml中指定的别名 > 类上的注解 org.apache.ibatis.type.Alias > 类名
           */
          String alias = child.getStringAttribute("alias");
          String type = child.getStringAttribute("type");
          try {
            Class<?> clazz = Resources.classForName(type);
            if (alias == null) {
              typeAliasRegistry.registerAlias(clazz);
            } else {
              typeAliasRegistry.registerAlias(alias, clazz);
            }
          } catch (ClassNotFoundException e) {
            throw new BuilderException("Error registering typeAlias for '" + alias + "'. Cause: " + e, e);
          }
        }
      }
    }
  }

  /**
   * 关键类
   * @see org.apache.ibatis.plugin.Interceptor
   * @see org.apache.ibatis.plugin.InterceptorChain
   * 将插件拦截器追加至配置的拦截器链中
   *
   * @param parent
   * @throws Exception
   */
  private void pluginElement(XNode parent) throws Exception {
    if (parent != null) {
      for (XNode child : parent.getChildren()) {
        String interceptor = child.getStringAttribute("interceptor");
        Properties properties = child.getChildrenAsProperties();
        /**
         * 可以使用之前注册的类型别名，否则需指定类全限定名
         */
        Interceptor interceptorInstance = (Interceptor) resolveClass(interceptor).newInstance();
        interceptorInstance.setProperties(properties);
        configuration.addInterceptor(interceptorInstance);
      }
    }
  }

  /**
   * 设置自定义的实体构造器，用于返回值初始化，即使用构造器初始化类
   * 默认为
   * @see org.apache.ibatis.reflection.factory.DefaultObjectFactory
   * 可自定义属性
   * @param context
   * @throws Exception
   */
  private void objectFactoryElement(XNode context) throws Exception {
    if (context != null) {
      String type = context.getStringAttribute("type");
      Properties properties = context.getChildrenAsProperties();
      ObjectFactory factory = (ObjectFactory) resolveClass(type).newInstance();
      factory.setProperties(properties);
      configuration.setObjectFactory(factory);
    }
  }

  /**
   * 初始化后的各种属性get set包装类
   * 关键类
   * @see org.apache.ibatis.reflection.wrapper.ObjectWrapperFactory
   * @see org.apache.ibatis.reflection.wrapper.ObjectWrapper
   * @see org.apache.ibatis.reflection.MetaObject
   * @param context
   * @throws Exception
   */
  private void objectWrapperFactoryElement(XNode context) throws Exception {
    if (context != null) {
      String type = context.getStringAttribute("type");
      ObjectWrapperFactory factory = (ObjectWrapperFactory) resolveClass(type).newInstance();
      configuration.setObjectWrapperFactory(factory);
    }
  }

  /**
   * 反射器工厂
   * @see Reflector 对于类的源数据管理，比如成员变量、方法等
   * @see ReflectorFactory
   * 默认为 {@link DefaultReflectorFactory}
   * @param context
   * @throws Exception
   */
  private void reflectorFactoryElement(XNode context) throws Exception {
    if (context != null) {
       String type = context.getStringAttribute("type");
       ReflectorFactory factory = (ReflectorFactory) resolveClass(type).newInstance();
       configuration.setReflectorFactory(factory);
    }
  }

  /**
   * 处理properties标签，里面的内容可被外部传入的属性替换，后续的配置均可引入此处解析完毕的属性配置
   * 这些属性都是可外部配置且可动态替换的，既可以在典型的 Java 属性文件中配置，亦可通过 properties 元素的子元素来传递
   * 然后其中的属性就可以在整个配置文件中被用来替换需要动态配置的属性值
   * 如果属性在不只一个地方进行了配置，那么 MyBatis 将按照下面的顺序来加载：
   *
   * 在 properties 元素体内指定的属性首先被读取。
   * 然后根据 properties 元素中的 resource 属性读取类路径下属性文件或根据 url 属性指定的路径读取属性文件，并覆盖已读取的同名属性。
   * 最后读取作为方法参数传递的属性，并覆盖已读取的同名属性。
   * 因此，通过方法参数传递的属性具有最高优先级，resource/url 属性中指定的配置文件次之，最低优先级的是 properties 属性中指定的属性
   *
   * 从 MyBatis 3.4.2 开始，你可以为占位符指定一个默认值
   * 这个特性默认是关闭的。如果你想为占位符指定一个默认值， 你应该添加一个指定的属性来开启这个特性
   * <property name="org.apache.ibatis.parsing.PropertyParser.enable-default-value" value="true"/>
   * 如果你已经使用 ":" 作为属性的键（如：db:username） ，或者你已经在 SQL 定义中使用 OGNL 表达式的三元运算符（如： ${tableName != null ? tableName : 'global_constants'}），你应该通过设置特定的属性来修改分隔键名和默认值的字符
   * <property name="org.apache.ibatis.parsing.PropertyParser.default-value-separator" value="?:"/>
   * @param context
   * @throws Exception
   */
  private void propertiesElement(XNode context) throws Exception {
    if (context != null) {
      Properties defaults = context.getChildrenAsProperties();
      String resource = context.getStringAttribute("resource");
      String url = context.getStringAttribute("url");
      if (resource != null && url != null) {
        throw new BuilderException("The properties element cannot specify both a URL and a resource based property file reference.  Please specify one or the other.");
      }
      if (resource != null) {
        /**
         *  当前类路径下加载资源,可能会抛出异常. ClassLoader顺序
         * @see ClassLoaderWrapper#getClassLoaders(java.lang.ClassLoader)
         * @see java.lang.ClassLoader#getResourceAsStream(java.lang.String)
         * resource应指定properties文件位置
         */
        defaults.putAll(Resources.getResourceAsProperties(resource));
      } else if (url != null) {
        //配置一个properties文件的链接
        defaults.putAll(Resources.getUrlAsProperties(url));
      }
      Properties vars = configuration.getVariables();
      if (vars != null) {
        defaults.putAll(vars);
      }
      // parser的更新后,所有的Node引用的variables也会更新， 所有的XNode均由parser产生，生成时会同步传入variables
      parser.setVariables(defaults);
      configuration.setVariables(defaults);
    }
  }

  private void settingsElement(Properties props) throws Exception {
    /**
     * 自动映射方式
     * 关闭自动映射
     * 不映射内嵌
     * 全部映射
     */
    configuration.setAutoMappingBehavior(AutoMappingBehavior.valueOf(props.getProperty("autoMappingBehavior", "PARTIAL")));
    /**
     * 自动映射时遇到未知数据库字段或类属性时的处理方式
     * nothing
     * 打印警告日志
     * 抛出异常 {@link SqlSessionException}
     *
     */
    configuration.setAutoMappingUnknownColumnBehavior(AutoMappingUnknownColumnBehavior.valueOf(props.getProperty("autoMappingUnknownColumnBehavior", "NONE")));
    /**
     * 全局缓存配置
     */
    configuration.setCacheEnabled(booleanValueOf(props.getProperty("cacheEnabled"), true));
    /**
     * 指定 Mybatis 创建具有延迟加载能力的对象所用到的代理工具。
     */
    configuration.setProxyFactory((ProxyFactory) createInstance(props.getProperty("proxyFactory")));
    /**
     * 延迟加载
     */
    configuration.setLazyLoadingEnabled(booleanValueOf(props.getProperty("lazyLoadingEnabled"), false));
    /**
     * 当开启时，任何方法的调用都会加载该对象的所有属性。 否则，每个属性会按需加载
     */
    configuration.setAggressiveLazyLoading(booleanValueOf(props.getProperty("aggressiveLazyLoading"), false));
    /**
     * 是否允许单一语句返回多结果集（需要驱动支持）。
     */
    configuration.setMultipleResultSetsEnabled(booleanValueOf(props.getProperty("multipleResultSetsEnabled"), true));
    /**
     * 使用列标签代替列名
     */
    configuration.setUseColumnLabel(booleanValueOf(props.getProperty("useColumnLabel"), true));
    /**
     * 主键自动生成
     */
    configuration.setUseGeneratedKeys(booleanValueOf(props.getProperty("useGeneratedKeys"), false));
    /**
     * 配置默认的执行器。SIMPLE 就是普通的执行器；REUSE 执行器会重用预处理语句（prepared statements）； BATCH 执行器将重用语句并执行批量更新。
     */
    configuration.setDefaultExecutorType(ExecutorType.valueOf(props.getProperty("defaultExecutorType", "SIMPLE")));
    /**
     * 语句超时时间
     */
    configuration.setDefaultStatementTimeout(integerValueOf(props.getProperty("defaultStatementTimeout"), null));
    /**
     * 为驱动的结果集获取数量（fetchSize）设置一个提示值。此参数只可以在查询设置中被覆盖。
     */
    configuration.setDefaultFetchSize(integerValueOf(props.getProperty("defaultFetchSize"), null));
    /**
     * 下划线映射为驼峰 即从经典数据库列名 A_COLUMN 到经典 Java 属性名 aColumn 的类似映射。
     */
    configuration.setMapUnderscoreToCamelCase(booleanValueOf(props.getProperty("mapUnderscoreToCamelCase"), false));
    /**
     * 允许在嵌套语句中使用分页（RowBounds）。如果允许使用则设置为 false
     */
    configuration.setSafeRowBoundsEnabled(booleanValueOf(props.getProperty("safeRowBoundsEnabled"), false));
    /**
     * 本地缓存级别
     */
    configuration.setLocalCacheScope(LocalCacheScope.valueOf(props.getProperty("localCacheScope", "SESSION")));
    /**
     * 当没有为参数提供特定的 JDBC 类型时，为空值指定 JDBC 类型
     */
    configuration.setJdbcTypeForNull(JdbcType.valueOf(props.getProperty("jdbcTypeForNull", "OTHER")));
    /**
     * 指定哪个对象的方法触发一次延迟加载
     */
    configuration.setLazyLoadTriggerMethods(stringSetValueOf(props.getProperty("lazyLoadTriggerMethods"), "equals,clone,hashCode,toString"));
    configuration.setSafeResultHandlerEnabled(booleanValueOf(props.getProperty("safeResultHandlerEnabled"), true));
    /**
     * 指定动态 SQL 生成的默认语言
     */
    configuration.setDefaultScriptingLanguage(resolveClass(props.getProperty("defaultScriptingLanguage")));
    @SuppressWarnings("unchecked")
    Class<? extends TypeHandler> typeHandler = (Class<? extends TypeHandler>)resolveClass(props.getProperty("defaultEnumTypeHandler"));
    /**
     * 枚举处理器
     */
    configuration.setDefaultEnumTypeHandler(typeHandler);
    /**
     * 指定当结果集中值为 null 的时候是否调用映射对象的 setter（map 对象时为 put）方法，
     */
    configuration.setCallSettersOnNulls(booleanValueOf(props.getProperty("callSettersOnNulls"), false));
    /**
     * 允许使用方法签名中的名称作为语句参数名称。 为了使用该特性，你的项目必须采用 Java 8 编译，并且加上 -parameters 选项
     */
    configuration.setUseActualParamName(booleanValueOf(props.getProperty("useActualParamName"), true));
    /**
     * 当返回行的所有列都是空时，MyBatis默认返回 null。 当开启这个设置时，MyBatis会返回一个空实例。
     */
    configuration.setReturnInstanceForEmptyRow(booleanValueOf(props.getProperty("returnInstanceForEmptyRow"), false));
    /**
     * 指定 MyBatis 增加到日志名称的前缀。
     */
    configuration.setLogPrefix(props.getProperty("logPrefix"));
    @SuppressWarnings("unchecked")
    Class<? extends Log> logImpl = (Class<? extends Log>)resolveClass(props.getProperty("logImpl"));
    /**
     * 日志实现
     */
    configuration.setLogImpl(logImpl);
    /**
     * Configuration factory class.
     * Used to create Configuration for loading deserialized unread properties.
     *
     * 指定一个提供 Configuration 实例的类。 这个被返回的 Configuration 实例用来加载被反序列化对象的延迟加载属性值。
     * 这个类必须包含一个签名为static Configuration getConfiguration() 的方法。（新增于 3.2.3）
     *
     * @see <a href='https://code.google.com/p/mybatis/issues/detail?id=300'>Issue 300 (google code)</a>
     */
    configuration.setConfigurationFactory(resolveClass(props.getProperty("configurationFactory")));
  }

  /**
   * 处理环境标签
   * @param context
   * @throws Exception
   */
  private void environmentsElement(XNode context) throws Exception {
    if (context != null) {
      /**
       * 程序中指定的环境优先于xml中配置的默认环境
       */
      if (environment == null) {
        environment = context.getStringAttribute("default");
      }
      for (XNode child : context.getChildren()) {
        String id = child.getStringAttribute("id");
        //仅处理指定的 environment 配置
        if (isSpecifiedEnvironment(id)) {
          /**
           * 事务管理器
           * 可自定义属性配置,属性配置需与相应的工厂类相关
           */
          TransactionFactory txFactory = transactionManagerElement(child.evalNode("transactionManager"));
          /**
           * 数据库连接池工厂
           * 可自定义属性配置,属性配置需与相应的工厂类相关
           */
          DataSourceFactory dsFactory = dataSourceElement(child.evalNode("dataSource"));
          DataSource dataSource = dsFactory.getDataSource();
          Environment.Builder environmentBuilder = new Environment.Builder(id)
              .transactionFactory(txFactory)
              .dataSource(dataSource);
          configuration.setEnvironment(environmentBuilder.build());
        }
      }
    }
  }

  /**
   * 数据库标识,mybatis会加载特定dataBaseId的SQL，加dataBaseId的优先于不带dataBaseId，同样的标签
   * @param context
   * @throws Exception
   */
  private void databaseIdProviderElement(XNode context) throws Exception {
    DatabaseIdProvider databaseIdProvider = null;
    if (context != null) {
      String type = context.getStringAttribute("type");
      // awful patch to keep backward compatibility
      if ("VENDOR".equals(type)) {
          type = "DB_VENDOR";
      }
      Properties properties = context.getChildrenAsProperties();
      databaseIdProvider = (DatabaseIdProvider) resolveClass(type).newInstance();
      databaseIdProvider.setProperties(properties);
    }
    Environment environment = configuration.getEnvironment();
    if (environment != null && databaseIdProvider != null) {
      String databaseId = databaseIdProvider.getDatabaseId(environment.getDataSource());
      configuration.setDatabaseId(databaseId);
    }
  }

  /**
   * 事务管理器
   * @param context
   * @return
   * @throws Exception
   */
  private TransactionFactory transactionManagerElement(XNode context) throws Exception {
    if (context != null) {
      String type = context.getStringAttribute("type");
      Properties props = context.getChildrenAsProperties();
      TransactionFactory factory = (TransactionFactory) resolveClass(type).newInstance();
      factory.setProperties(props);
      return factory;
    }
    throw new BuilderException("Environment declaration requires a TransactionFactory.");
  }

  /**
   * 数据库连接池
   * @param context
   * @return
   * @throws Exception
   */
  private DataSourceFactory dataSourceElement(XNode context) throws Exception {
    if (context != null) {
      String type = context.getStringAttribute("type");
      Properties props = context.getChildrenAsProperties();
      DataSourceFactory factory = (DataSourceFactory) resolveClass(type).newInstance();
      factory.setProperties(props);
      return factory;
    }
    throw new BuilderException("Environment declaration requires a DataSourceFactory.");
  }

  /**
   * 数据库类型与java类型的转换器  java <-> jdbc
   * @see java.lang.reflect.Type
   * @see JdbcType
   * @see TypeHandler
   * @see org.apache.ibatis.type.TypeHandlerRegistry
   * @param parent
   * @throws Exception
   */
  private void typeHandlerElement(XNode parent) throws Exception {
    if (parent != null) {
      for (XNode child : parent.getChildren()) {
        /**
         * 支持包名指定，包下的所有路径下的类均会处理
         * @see org.apache.ibatis.type.MappedJdbcTypes 设置可以处理的 jdbc类型
         * @see org.apache.ibatis.type.MappedTypes 设置可以处理的java 类型
         */
        if ("package".equals(child.getName())) {
          String typeHandlerPackage = child.getStringAttribute("name");
          typeHandlerRegistry.register(typeHandlerPackage);
        } else {
          String javaTypeName = child.getStringAttribute("javaType");
          String jdbcTypeName = child.getStringAttribute("jdbcType");
          String handlerTypeName = child.getStringAttribute("handler");
          //可以使用前面指定的别名 typeAlias
          Class<?> javaTypeClass = resolveClass(javaTypeName);
          /**
           * 不可使用别名 org.apache.ibatis.type.JdbcType  java.sql.Types
           */
          JdbcType jdbcType = resolveJdbcType(jdbcTypeName);
          //可以使用前面指定的别名 typeAlias
          Class<?> typeHandlerClass = resolveClass(handlerTypeName);
          if (javaTypeClass != null) {
            if (jdbcType == null) {
              typeHandlerRegistry.register(javaTypeClass, typeHandlerClass);
            } else {
              typeHandlerRegistry.register(javaTypeClass, jdbcType, typeHandlerClass);
            }
          } else {
            typeHandlerRegistry.register(typeHandlerClass);
          }
        }
      }
    }
  }

  /**
   * 解析 接口、XML资源 需单独查看源码,可对照着看
   * @param parent
   * @throws Exception
   */
  private void mapperElement(XNode parent) throws Exception {
    if (parent != null) {
      for (XNode child : parent.getChildren()) {
        //可以按照包名处理
        if ("package".equals(child.getName())) {
          String mapperPackage = child.getStringAttribute("name");
          //扫码指定包名下的接口
          /**
           * 将接口做代理,并解析接口里的注解SQL
           * @see MapperRegistry 注册器
           * @see MapperProxyFactory 代理工厂
           * @see org.apache.ibatis.binding.MapperProxy MAPPER代理
           * @see MapperMethod Mapper方法
           * @see MapperAnnotationBuilder
           *
           * XML资源解析
           * @see XMLMapperBuilder
           *
           */
          configuration.addMappers(mapperPackage);
        } else {
          String resource = child.getStringAttribute("resource");
          String url = child.getStringAttribute("url");
          String mapperClass = child.getStringAttribute("class");
          if (resource != null && url == null && mapperClass == null) {
            // classpath资源解析 xml资源
            ErrorContext.instance().resource(resource);
            InputStream inputStream = Resources.getResourceAsStream(resource);
            XMLMapperBuilder mapperParser = new XMLMapperBuilder(inputStream, configuration, resource, configuration.getSqlFragments());
            mapperParser.parse();
          } else if (resource == null && url != null && mapperClass == null) {
            // 远程资源解析 xml资源
            ErrorContext.instance().resource(url);
            InputStream inputStream = Resources.getUrlAsStream(url);
            XMLMapperBuilder mapperParser = new XMLMapperBuilder(inputStream, configuration, url, configuration.getSqlFragments());
            mapperParser.parse();
          } else if (resource == null && url == null && mapperClass != null) {
            // Mapper接口解析
            Class<?> mapperInterface = Resources.classForName(mapperClass);
            configuration.addMapper(mapperInterface);
          } else {
            throw new BuilderException("A mapper element may only specify a url, resource or class, but not more than one.");
          }
        }
      }
    }
  }

  private boolean isSpecifiedEnvironment(String id) {
    if (environment == null) {
      throw new BuilderException("No environment specified.");
    } else if (id == null) {
      throw new BuilderException("Environment requires an id attribute.");
    } else if (environment.equals(id)) {
      return true;
    }
    return false;
  }

}
