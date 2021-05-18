package cwms.radar.api;

import java.sql.SQLException;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.QueryParam;

import com.codahale.metrics.*;

import cwms.radar.data.CwmsDataManager;
import cwms.radar.data.dto.Catalog;
import cwms.radar.data.dto.Office;
import cwms.radar.formatters.ContentType;
import cwms.radar.formatters.Formats;

import static com.codahale.metrics.MetricRegistry.*;

import io.javalin.apibuilder.CrudHandler;
import io.javalin.http.Context;
import io.javalin.plugin.openapi.annotations.*;

public class CatalogController implements CrudHandler{

    private static final Logger logger = Logger.getLogger(CatalogController.class.getName());


    private final MetricRegistry metrics;// = new MetricRegistry();
    private final Meter getAllRequests;// = metrics.meter(OfficeController.class.getName()+"."+"getAll.count");
    private final Timer getAllRequestsTime;// =metrics.timer(OfficeController.class.getName()+"."+"getAll.time");
    private final Meter getOneRequest;
    private final Timer getOneRequestTime;
    private final Histogram requestResultSize;

    public CatalogController(MetricRegistry metrics){
        this.metrics=metrics;
        String className = OfficeController.class.getName();
        getAllRequests = this.metrics.meter(name(className,"getAll","count"));
        getAllRequestsTime = this.metrics.timer(name(className,"getAll","time"));
        getOneRequest = this.metrics.meter(name(className,"getOne","count"));
        getOneRequestTime = this.metrics.timer(name(className,"getOne","time"));
        requestResultSize = this.metrics.histogram((name(className,"results","size")));
    }

    @OpenApi(tags = {"Catalog"},ignore = true)
    @Override
    public void create(Context ctx) {
        ctx.status(HttpServletResponse.SC_NOT_IMPLEMENTED).result("cannot perform this action");        
    }

    @OpenApi(tags = {"Catalog"},ignore = true)
    @Override
    public void delete(Context ctx, String entry) {
        ctx.status(HttpServletResponse.SC_NOT_IMPLEMENTED).result("cannot perform this action");
    }

    @OpenApi(tags = {"Catalog"},ignore = true)
    @Override
    public void getAll(Context ctx) {
        ctx.status(HttpServletResponse.SC_NOT_IMPLEMENTED).result("cannot perform this action");        
    }

    @OpenApi(                              
        queryParams = {
            @OpenApiParam(name="page",
                          required = false, 
                          type=Integer.class, 
                          description = "This end point can return a lot of data, this identifies where in the request you are."),
            @OpenApiParam(name="office",
                          required = false,
                          description = "3-4 letter office name representing the district you want to isolate data to."
            )
        },
        pathParams = {
            @OpenApiParam(name="dataSet",
                          required = false,
                          type = CatalogableEndpoint.class,                                                                          
                          description = "A list of what data? E.g. Timeseries, Locations, Ratings, etc")
        },
        responses = { @OpenApiResponse(status="200",
                                       description = "A list of everything, unless you set some parameters.",
                                       content = {
                                           @OpenApiContent(from = Catalog.class, type=Formats.JSONV2)                                           
                                       }
                      ),
                      @OpenApiResponse(status="501",description = "The format requested is not implemented"),
                      @OpenApiResponse(status="400", description = "Invalid Parameter combination")
                    },        
        tags = {"Catalog"}
    )
    @Override
    public void getOne(Context ctx, String dataSet) {
        getAllRequests.mark();
        try (
            final Timer.Context time_context = getAllRequestsTime.time();
            CwmsDataManager cdm = new CwmsDataManager(ctx);
        ) {
            int page = ctx.queryParam("page",Integer.class,"1").getValue().intValue();
            Optional<String> office = Optional.ofNullable(
                                         ctx.queryParam("office", String.class, null)
                                            .check( ofc -> Office.validOfficeCanNull(ofc)                
                                          ).getOrNull());
            String acceptHeader = ctx.header("Accept");
            ContentType contentType = Formats.parseHeaderAndQueryParm(acceptHeader, null);
            Catalog cat = null;
            if( "timeseries".equalsIgnoreCase(dataSet)){
                cat = cdm.getTimeSeriesCatalog(page, office );
            }
            if( cat != null ){
                String data = Formats.format(contentType, cat);
                ctx.result(data).contentType(contentType.toString());
            } else {
                ctx.result("Cannot create catalog of requested information").status(HttpServletResponse.SC_BAD_REQUEST);
            }
            
        } catch( SQLException er) {
            logger.log(Level.SEVERE, "failed to process catalog request", er);
            ctx.status(HttpServletResponse.SC_INTERNAL_SERVER_ERROR).result("Failed to process request");
        }
        
    }

    @OpenApi(tags = {"Catalog"},ignore = true)
    @Override
    public void update(Context ctx, String entry) {
        ctx.status(HttpServletResponse.SC_NOT_IMPLEMENTED).result("cannot perform this action");   
    }
    
}