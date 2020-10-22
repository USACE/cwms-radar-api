package cwms.radar.data;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import cwms.radar.data.dao.Office;
import io.javalin.http.Context;

public class CwmsDataManager implements AutoCloseable {
    private static final Logger logger = Logger.getLogger("CwmsDataManager");
    public static final String ALL_OFFICES_QUERY = "select office_id,long_name,office_type,report_to_office_id from cwms_20.av_office"; //TODO: put a where clause in here with everything 
    public static final String SINGLE_OFFICE = "select office_id,long_name,office_type,report_to_office_id from cwms_20.av_office where office_id=?";


    private Connection conn;

    public CwmsDataManager(Context ctx){
        conn = ctx.attribute("database");
    }

    @Override
    public void close() throws SQLException {        
        conn.close();
    }

    public List<Office> getOffices(){        
        try (                
                PreparedStatement stmt = conn.prepareStatement(ALL_OFFICES_QUERY);
                ResultSet rs = stmt.executeQuery();
            ) {
                List<Office> offices = new ArrayList<>();
                while (rs.next()) {
                    Office l = new Office(rs);            
                    offices.add(l);
                }
                return offices;
        } catch( SQLException err ){
            
        }
        return null;
    }

	public Office getOfficeById(String office_id) {
        
        try(
            PreparedStatement stmt = conn.prepareStatement(SINGLE_OFFICE);
        ) {
            stmt.setString(1, office_id);
            try(
                ResultSet rs = stmt.executeQuery();
            ){ 
                if(rs.next()){
                    return new Office(rs);
                }
            }
        } catch( SQLException err ){
            logger.warning("Failed to process database request" + err.getLocalizedMessage() );
        }
		return null;
	}
    
}