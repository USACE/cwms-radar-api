package cwms.radar.data;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import cwms.radar.data.dto.Catalog;
import cwms.radar.data.dto.TimeSeries;
import cwms.radar.data.dto.catalog.CatalogEntry;
import cwms.radar.data.dto.catalog.LocationAlias;
import cwms.radar.data.dto.catalog.LocationCatalogEntry;
import cwms.radar.data.dto.catalog.TimeseriesCatalogEntry;
import io.javalin.http.Context;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Record1;
import org.jooq.Record3;
import org.jooq.Record5;
import org.jooq.Result;
import org.jooq.SQL;
import org.jooq.SQLDialect;
import org.jooq.SelectConditionStep;
import org.jooq.SelectJoinStep;
import org.jooq.SelectSelectStep;
import org.jooq.Table;
import org.jooq.conf.ParamType;
import org.jooq.impl.DSL;

import usace.cwms.db.jooq.codegen.packages.CWMS_CAT_PACKAGE;
import usace.cwms.db.jooq.codegen.packages.CWMS_ENV_PACKAGE;
import usace.cwms.db.jooq.codegen.packages.CWMS_LEVEL_PACKAGE;
import usace.cwms.db.jooq.codegen.packages.CWMS_RATING_PACKAGE;
import usace.cwms.db.jooq.codegen.packages.CWMS_ROUNDING_PACKAGE;
import usace.cwms.db.jooq.codegen.packages.CWMS_TS_PACKAGE;
import usace.cwms.db.jooq.codegen.packages.CWMS_UTIL_PACKAGE;

import static org.jooq.impl.DSL.asterisk;
import static org.jooq.impl.DSL.count;
import static usace.cwms.db.jooq.codegen.tables.AV_CWMS_TS_ID2.AV_CWMS_TS_ID2;
import static usace.cwms.db.jooq.codegen.tables.AV_LOC.AV_LOC;
import static usace.cwms.db.jooq.codegen.tables.AV_LOC_ALIAS.AV_LOC_ALIAS;
import static usace.cwms.db.jooq.codegen.tables.AV_LOC_GRP_ASSGN.AV_LOC_GRP_ASSGN;


public class CwmsDataManager implements AutoCloseable {
    private static final Logger logger = Logger.getLogger("CwmsDataManager");
    public static final String FAILED = "Failed to process database request";

    private Connection conn;
    private DSLContext dsl;

    public CwmsDataManager(Context ctx) throws SQLException{
        this(ctx.attribute("database"), ctx.attribute("office_id"));
    }

    public CwmsDataManager(Connection conn, String officeId) throws SQLException{
        this.conn = conn;
        dsl = DSL.using(conn, SQLDialect.ORACLE11G);

        setOfficeId(officeId);
    }

    private void setOfficeId(String officeId)
    {
        CWMS_ENV_PACKAGE.call_SET_SESSION_OFFICE_ID(dsl.configuration(), officeId);
    }

    @Override
    public void close() throws SQLException {
        conn.close();
    }



	public String getRatings(String names, String format, String unit, String datum, String office, String start,
			String end, String timezone, String size) {
        return CWMS_RATING_PACKAGE.call_RETRIEVE_RATINGS_F(dsl.configuration(),
                names, format, unit, datum, start, end, timezone, office);
	}

	public String getUnits(String format) {
        return CWMS_CAT_PACKAGE.call_RETRIEVE_UNITS_F(dsl.configuration(), format);
	}

	public String getParameters(String format){
        return CWMS_CAT_PACKAGE.call_RETRIEVE_PARAMETERS_F(dsl.configuration(), format);
    }

	public String getTimeZones(String format) {
            return CWMS_CAT_PACKAGE.call_RETRIEVE_TIME_ZONES_F(dsl.configuration(), format);
    }

	public String getLocationLevels(String format, String names, String office, String unit, String datum, String begin,
			String end, String timezone) {
        return CWMS_LEVEL_PACKAGE.call_RETRIEVE_LOCATION_LEVELS_F(dsl.configuration(),
                names, format, office,unit,datum, begin, end, timezone);
    }

	public String getTimeseries(String format, String names, String office, String units, String datum, String begin,
			String end, String timezone) {
        return CWMS_TS_PACKAGE.call_RETRIEVE_TIME_SERIES_F(dsl.configuration(),
                names, format, units,datum, begin, end, timezone, office);
	}

    public TimeSeries getTimeseries(String page, int pageSize, String names, String office, String units, String datum, String begin, String end, String timezone) {
        String cursor = null;
        Timestamp tsCursor = null;
        Integer total = null;

        if(begin == null)
            begin = ZonedDateTime.now().minusDays(1).toLocalDateTime().toString();
        if(end == null)
            end = ZonedDateTime.now().toLocalDateTime().toString();

        if(page != null && !page.isEmpty())
        {
            String[] parts = TimeSeries.decodeCursor(page);

            logger.info("Decoded cursor");
            for( String p: parts){
                logger.info(p);
            }

            if(parts.length > 1)
            {
                cursor = parts[0];
                tsCursor = Timestamp.from(Instant.ofEpochMilli(Long.parseLong(parts[0])));

                if(parts.length > 2)
                    total = Integer.parseInt(parts[1]);

                // Use the pageSize from the original cursor, for consistent paging
                pageSize = Integer.parseInt(parts[parts.length - 1]);   // Last item is pageSize
            }
        }

        ZoneId zone = timezone == null ? ZoneOffset.UTC.normalized() : ZoneId.of(timezone);

        // Parse the date time in the best format it can find. Timezone is optional, but use it if it's found.
        TemporalAccessor beginParsed = DateTimeFormatter.ISO_DATE_TIME.parseBest(begin, ZonedDateTime::from, LocalDateTime::from);
        TemporalAccessor endParsed = DateTimeFormatter.ISO_DATE_TIME.parseBest(end, ZonedDateTime::from, LocalDateTime::from);

        ZonedDateTime beginTime = beginParsed instanceof ZonedDateTime ? ZonedDateTime.from(beginParsed) : LocalDateTime.from(beginParsed).atZone(zone);
        // If the end time doesn't have a timezone, but begin did, use begin's timezone as end's.
        ZonedDateTime endTime = endParsed instanceof ZonedDateTime ? ZonedDateTime.from(endParsed) : LocalDateTime.from(endParsed).atZone(beginTime.getZone());

        if(timezone == null) {
            if(beginTime.getZone().equals(beginTime.getOffset()))
                throw new IllegalArgumentException("Time cannot contain only an offset without the timezone.");
            // If no timezone was found, get it from begin_time
            zone = beginTime.getZone();
        }

        final String recordCursor = cursor;
        final int recordPageSize = pageSize;

        Field<String> officeId = CWMS_UTIL_PACKAGE.call_GET_DB_OFFICE_ID(office != null ? DSL.val(office) : CWMS_UTIL_PACKAGE.call_USER_OFFICE_ID());
        Field<String> tsId = CWMS_TS_PACKAGE.call_GET_TS_ID__2(DSL.val(names), officeId);
        Field<BigDecimal> tsCode = CWMS_TS_PACKAGE.call_GET_TS_CODE__2(tsId, officeId);
        Field<String> unit = units.compareToIgnoreCase("SI") == 0 || units.compareToIgnoreCase("EN") == 0 ?
            CWMS_UTIL_PACKAGE.call_GET_DEFAULT_UNITS(CWMS_TS_PACKAGE.call_GET_BASE_PARAMETER_ID(tsCode), DSL.val(units, String.class)) :
            DSL.val(units, String.class);

        // This code assumes the database timezone is in UTC (per Oracle recommendation)
        // Wrap in table() so JOOQ can parse the result
        @SuppressWarnings("deprecated")
        SQL retrieveTable = DSL.sql("table(" + CWMS_TS_PACKAGE.call_RETRIEVE_TS_OUT_TAB(
            tsId,
            unit,
            CWMS_UTIL_PACKAGE.call_TO_TIMESTAMP__2(DSL.val(beginTime.toInstant().toEpochMilli())),
            CWMS_UTIL_PACKAGE.call_TO_TIMESTAMP__2(DSL.val(endTime.toInstant().toEpochMilli())),
            DSL.inline("UTC", String.class),    // All times are sent as UTC to the database, regardless of requested timezone.
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            officeId) + ")"
        );

        SelectSelectStep<Record5<String,String,String,BigDecimal,Integer>> metadataQuery = dsl.select(
            tsId.as("NAME"),
            officeId.as("OFFICE_ID"),
            unit.as("UNITS"),
            CWMS_TS_PACKAGE.call_GET_INTERVAL(tsId).as("INTERVAL"),
            // If we don't know the total, fetch it from the database (only for first fetch).
            // Total is only an estimate, as it can change if fetching current data, or the timeseries otherwise changes between queries.
            total != null ? DSL.val(total).as("TOTAL") : DSL.selectCount().from(retrieveTable).asField("TOTAL")
        );

        logger.info( metadataQuery.getSQL(ParamType.INLINED));

        TimeSeries timeseries = metadataQuery.fetchOne(tsMetadata ->
                new TimeSeries(recordCursor,
                    recordPageSize,
                    tsMetadata.getValue("TOTAL", Integer.class),
                    tsMetadata.getValue("NAME", String.class),
                    tsMetadata.getValue("OFFICE_ID", String.class),
                    beginTime,
                    endTime,
                    tsMetadata.getValue("UNITS", String.class),
                    Duration.ofMinutes(tsMetadata.get("INTERVAL") == null ? 0 : tsMetadata.getValue("INTERVAL", Long.class)))
        );

        if(pageSize != 0) {
            SelectConditionStep<Record3<Timestamp, Double, BigDecimal>> query = dsl.select(
                DSL.field("DATE_TIME", Timestamp.class).as("DATE_TIME"),
                CWMS_ROUNDING_PACKAGE.call_ROUND_DD_F(DSL.field("VALUE", Double.class), DSL.inline("5567899996"), DSL.inline('T')).as("VALUE"),
                CWMS_TS_PACKAGE.call_NORMALIZE_QUALITY(DSL.nvl(DSL.field("QUALITY_CODE", Integer.class), DSL.inline(5))).as("QUALITY_CODE")
            )
            .from(retrieveTable)
            .where(DSL.field("DATE_TIME", Timestamp.class)
                .greaterOrEqual(CWMS_UTIL_PACKAGE.call_TO_TIMESTAMP__2(
                                    DSL.nvl(DSL.val(tsCursor == null ? null : tsCursor.toInstant().toEpochMilli()),
                                            DSL.val(beginTime.toInstant().toEpochMilli())))))
            .and(DSL.field("DATE_TIME", Timestamp.class)
                .lessOrEqual(CWMS_UTIL_PACKAGE.call_TO_TIMESTAMP__2(DSL.val(endTime.toInstant().toEpochMilli())))
            );

            if(pageSize > 0)
                query.limit(DSL.val(pageSize + 1));

            logger.info( query.getSQL(ParamType.INLINED));

            query.fetchInto(tsRecord -> {
                    timeseries.addValue(
                        tsRecord.getValue("DATE_TIME", Timestamp.class),
                        tsRecord.getValue("VALUE", Double.class),
                        tsRecord.getValue("QUALITY_CODE", Integer.class)
                    );
                }
            );
        }
        return timeseries;
    }

    public Catalog getTimeSeriesCatalog(String page, int pageSize, Optional<String> office){
        int total = 0;
        String tsCursor = "*";
        if( page == null || page.isEmpty() ){
            SelectJoinStep<Record1<Integer>> count = dsl.select(count(asterisk())).from(AV_CWMS_TS_ID2);
            if( office.isPresent() ){
                count.where(AV_CWMS_TS_ID2.DB_OFFICE_ID.eq(office.get()));
            }
            total = count.fetchOne().value1().intValue();
        } else {
            logger.info("getting non-default page");
            // get totally from page
            String[] parts = Catalog.decodeCursor(page, "|||");

            logger.info("decoded cursor: " + String.join("|||", parts));
            for( String p: parts){
                logger.info(p);
            }

            if(parts.length > 1) {
                tsCursor = parts[0].split("\\/")[1];
                total = Integer.parseInt(parts[1]);
            }
        }

        SelectJoinStep<Record3<String, String, String>> query = dsl.select(
                                        AV_CWMS_TS_ID2.DB_OFFICE_ID,
                                        AV_CWMS_TS_ID2.CWMS_TS_ID,
                                        AV_CWMS_TS_ID2.UNIT_ID
                                    )
                                .from(AV_CWMS_TS_ID2);

        if( office.isPresent() ){
            query.where(AV_CWMS_TS_ID2.DB_OFFICE_ID.upper().eq(office.get().toUpperCase()))
                 .and(AV_CWMS_TS_ID2.CWMS_TS_ID.upper().greaterThan(tsCursor));
        } else {
            query.where(AV_CWMS_TS_ID2.CWMS_TS_ID.upper().gt(tsCursor));
        }
        query.orderBy(AV_CWMS_TS_ID2.CWMS_TS_ID).limit(pageSize);
        logger.info( query.getSQL(ParamType.INLINED));
        Result<Record3<String, String, String>> result = query.fetch();
        List<? extends CatalogEntry> entries = result.stream()
                //.map( e -> e.into(usace.cwms.db.jooq.codegen.tables.records.AV_CWMS_TIMESERIES_ID2) )
                .map( e -> new TimeseriesCatalogEntry(e.get(AV_CWMS_TS_ID2.DB_OFFICE_ID),
                                                      e.get(AV_CWMS_TS_ID2.CWMS_TS_ID),
                                                      e.get(AV_CWMS_TS_ID2.UNIT_ID) )
                )
                .collect(Collectors.toList());
        Catalog cat = new Catalog(tsCursor,total,pageSize,entries);
        return cat;
    }




}
