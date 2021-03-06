package mil.army.usace.hec;

import java.util.logging.Level;
import java.util.logging.Logger;

import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.servlets.MetricsServlet;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import cwms.radar.api.CatalogController;
import cwms.radar.api.ClobController;
import cwms.radar.api.LevelsController;
import cwms.radar.api.LocationCategoryController;
import cwms.radar.api.LocationController;
import cwms.radar.api.LocationGroupController;
import cwms.radar.api.OfficeController;
import cwms.radar.api.ParametersController;
import cwms.radar.api.RatingController;
import cwms.radar.api.TimeSeriesCategoryController;
import cwms.radar.api.TimeSeriesController;
import cwms.radar.api.TimeSeriesGroupController;
import cwms.radar.api.TimeZoneController;
import cwms.radar.api.UnitsController;
import cwms.radar.api.enums.UnitSystem;
import cwms.radar.formatters.Formats;
import io.javalin.Javalin;
import io.javalin.core.plugin.Plugin;
import io.javalin.core.validation.JavalinValidation;
import io.javalin.plugin.json.JavalinJackson;
import io.javalin.plugin.openapi.OpenApiOptions;
import io.javalin.plugin.openapi.OpenApiPlugin;
import io.javalin.plugin.openapi.ui.SwaggerOptions;
import io.swagger.v3.oas.models.info.Info;
import org.apache.tomcat.jdbc.pool.DataSource;
import org.eclipse.jetty.servlet.ServletHolder;
import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.PolicyFactory;

import static io.javalin.apibuilder.ApiBuilder.crud;
import static io.javalin.apibuilder.ApiBuilder.get;
import static io.javalin.apibuilder.ApiBuilder.path;


public class RadarAPI {
    private static final Logger logger = Logger.getLogger(RadarAPI.class.getName());
    private static final MetricRegistry metrics = new MetricRegistry();
    private static final Meter total_requests = metrics.meter("radar.total_requests");
    public static void main(String[] args){
        DataSource ds = new DataSource();
        try{
            ds.setDriverClassName(getconfig("RADAR_JDBC_DRIVER","oracle.jdbc.driver.OracleDriver"));
            ds.setUrl(getconfig("RADAR_JDBC_URL","jdbc:oracle:thin:@localhost/CWMSDEV"));
            ds.setUsername(getconfig("RADAR_JDBC_USERNAME"));
            ds.setPassword(getconfig("RADAR_JDBC_PASSWORD"));
            ds.setInitialSize(Integer.parseInt(getconfig("RADAR_POOL_INIT_SIZE","5")));
            ds.setMaxActive(Integer.parseInt(getconfig("RADAR_POOL_MAX_ACTIVE","10")));
            ds.setMaxIdle(Integer.parseInt(getconfig("RADAR_POOL_MAX_IDLE","5")));
            ds.setMinIdle(Integer.parseInt(getconfig("RADAR_POOL_MIN_IDLE","2")));
        } catch( Exception err ){
            logger.log(Level.SEVERE,"Required Parameter not set in environment",err);
            System.exit(1);
        }

        PolicyFactory sanitizer = new HtmlPolicyBuilder().disallowElements("<script>").toFactory();
        JavalinValidation.register(UnitSystem.class, v -> UnitSystem.systemFor(v) );
        int port = Integer.parseInt(System.getProperty("RADAR_LISTEN_PORT","7000"));
        ObjectMapper om = JavalinJackson.getObjectMapper();
        om.setPropertyNamingStrategy(PropertyNamingStrategies.KEBAB_CASE);
        om.registerModule(new JavaTimeModule());

        JavalinJackson.configure(om);
        Javalin app = Javalin.create( config -> {
            config.defaultContentType = "application/json";
            config.contextPath = "/";
            config.registerPlugin(new OpenApiPlugin(getOpenApiOptions()));
            if( System.getProperty("RADAR_DEBUG_LOGGING","false").equalsIgnoreCase("true")){
                config.enableDevLogging();
            }
            config.requestLogger( (ctx,ms) -> logger.info(ctx.toString()));
            config.configureServletContextHandler( sch -> {
                sch.addServlet(new ServletHolder(new MetricsServlet(metrics)),"/metrics/*");
            });
            config.addStaticFiles("/static");
        }).attribute(PolicyFactory.class,sanitizer)

          .before( ctx -> {
            ctx.header("X-Content-Type-Options","nosniff");
            ctx.header("X-Frame-Options","SAMEORIGIN");
            ctx.header("X-XSS-Protection", "1; mode=block");
            ctx.attribute("database",ds.getConnection());
            /* authorization on connection setup will go here
            Connection conn = ctx.attribute("db");
            */
            logger.info(ctx.header("accept"));
            total_requests.mark();
        }).after( ctx -> {
            ((java.sql.Connection)ctx.attribute("database")).close();
        })
        .exception(UnsupportedOperationException.class, (e,ctx) -> {
            ctx.status(501);
            ctx.json(sanitizer.sanitize(e.getMessage()));
        })
        .exception(Exception.class, (e,ctx) -> {
            ctx.status(500);
            ctx.json("There was an error processing your request");
            logger.log(Level.WARNING,"error on request: " + ctx.req.getRequestURI(),e);
        })
        .routes( () -> {
            //get("/", ctx -> { ctx.result("welcome to the CWMS REST API").contentType(Formats.PLAIN);});
            crud("/locations/:location_code", new LocationController(metrics));
            crud("/location/category/:category-id", new LocationCategoryController(metrics));
            crud("/location/group/:group-id", new LocationGroupController(metrics));
            crud("/offices/:office", new OfficeController(metrics));
            crud("/units/:unit_name", new UnitsController(metrics));
            crud("/parameters/:param_name", new ParametersController(metrics));
            crud("/timezones/:zone", new TimeZoneController(metrics));
            crud("/levels/:location", new LevelsController(metrics));
            crud("/timeseries/:timeseries", new TimeSeriesController(metrics));
            crud("/timeseries/category/:category-id", new TimeSeriesCategoryController(metrics));
            crud("/timeseries/group/:group-id", new TimeSeriesGroupController(metrics));
            crud("/ratings/:rating", new RatingController(metrics));
            crud("/catalog/:dataSet", new CatalogController(metrics));

            crud("/clobs/:clob-id", new ClobController(metrics));
        }).start(port);

    }

    private static OpenApiOptions getOpenApiOptions() {
        Info applicationInfo = new Info().version("2.0").description("CWMS REST API for Data Retrieval");
        OpenApiOptions options = new OpenApiOptions(applicationInfo)
                    .path("/swagger-docs")
                    .swagger( new SwaggerOptions("/swagger-ui.html"))
                    .activateAnnotationScanningFor("cwms.radar.api");
        return options;
    }

    private static String getconfig(String envName){
        return System.getenv(envName);
    }
    private static String getconfig(String envName,String defaultVal){
        String val = System.getenv(envName);
        return val != null ? val : defaultVal;
    }
}
