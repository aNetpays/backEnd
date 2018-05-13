package com.anet.rest;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.text.DateFormat;
import java.text.DateFormatSymbols;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.FormParam;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sun.jersey.core.header.FormDataContentDisposition;
import org.glassfish.jersey.media.multipart.FormDataParam;

import mx.openpay.client.Card;
import mx.openpay.client.Customer;
import mx.openpay.client.core.OpenpayAPI;
import mx.openpay.client.exceptions.OpenpayServiceException;
import mx.openpay.client.exceptions.ServiceUnavailableException;


/**
* The AnetApi program implements a REST API which allows
* the user to connect to the anet_for_users database and
* apply CRUD operations in any of the tables.
*
* @author  Javier Arias
* @version 1.0
* @since   2018-02-12 
*/
@Path("/")
public class AnetApi {
	// These are the constants used for database connection. There may need to be changes here for security reasons
	private Connection conn = null;
	private static final String URL = "jdbc:mysql://104.198.46.93:3306/paymentAntad";
    private static final String DRIVER = "com.mysql.jdbc.Driver";
    private static final String USER = "xavi";
    private static final String PWD = "Asdf098@sdf";
    
    /**
     * This method uses the MySQL jdbc driver (included in the build path)
     * to start the connection to the database.
     */
    private void startConn() throws SQLException{
        if (conn == null) {
        	try {
                Class.forName(DRIVER);
                conn = DriverManager.getConnection(URL, USER, PWD);
            } catch (ClassNotFoundException ex) {
                Logger.getLogger(AnetApi.class.getName()).log(Level.SEVERE, null, ex);
            } catch (SQLException ex2) {
                Logger.getLogger(AnetApi.class.getName()).log(Level.SEVERE, null, ex2);
            } 
    	}
    }

    /**
     * This method destroys the connection to the aNet database
     * to prevent too many sessions being open at the same time and
     * and causing issues
     */
    private void endConn() {
        if (conn != null) {
            try {
            	conn.close();
            } catch (Exception ex) {
            	System.out.println(ex.getMessage());
                Logger.getLogger(AnetApi.class.getName()).log(Level.SEVERE, null, ex);
            }
            conn = null;
        }
    }
    
    /**
     * This method checks to see if a string value is a number
     * @param str The string to be checked
     * @return boolean This returns true if the string is a number, otherwise false
     */
    public static boolean isNumeric(String str) {
    	try {  
    		@SuppressWarnings("unused")
			double d = Double.parseDouble(str);  
    	} catch(NumberFormatException nfe) {  
    		return false;  
    	}
    	return true;
    }
	
    /**
     * This method converts the result from an SQL query to a type that the GSON
     * json converter can use to convert the result to JSON format.
     * @param resultSet The result set from a successful query
     * @return List<Map<String, Object>> This returns a properly formatted list of results
     * (for queries with multiple results).
     */
	protected List<Map<String, Object>> getEntitiesFromResultSet(ResultSet resultSet, boolean isTransaction) throws SQLException {
        ArrayList<Map<String, Object>> entities = new ArrayList<>();
        while (resultSet.next()) {
        	entities.add(getEntityFromResultSet(resultSet, isTransaction));
        }
        return entities;
    }

	/**
	   * This method stores the result set of a single result from a query in a format
	   * which can then be easily transformed to JSON data by the GSON converter
	   * @param resultSet The result from a successful SQL query
	   * @return Map<String, Object> This returns a single row from a query result.
	   */
    protected Map<String, Object> getEntityFromResultSet(ResultSet resultSet, boolean isTransaction) throws SQLException {
        ResultSetMetaData metaData = resultSet.getMetaData();
        int columnCount = metaData.getColumnCount();

        Map<String, Object> resultsMap = new HashMap<>();
        for (int i = 1; i <= columnCount; ++i) {
            String columnName = metaData.getColumnName(i).toLowerCase();
            Object object = resultSet.getObject(i);
            // This converts the timestamp to human readable standards before sending it in the json string
            if (columnName.equals("created_date") || columnName.equals("last_update_date") || columnName.equals("last_login_date")) {
                Timestamp ts = (Timestamp)object;
                if (ts != null) {
                    DateFormat df = new SimpleDateFormat("dd-MMM-yyyy hh:mm:ss a", new DateFormatSymbols(new Locale("en", "US")));
                    String timeStamp = df.format(ts);
                    object = (String)timeStamp;
                } else {
                    object = null;
                }
            }
            if (object != null) {
            	resultsMap.put(columnName, object.toString());
            } else {
            	resultsMap.put(columnName, object);
            }
            if (isTransaction) {
            	if (columnName.equals("associate_id")) {
            		String query = "SELECT * FROM associates WHERE associate_id = " + object.toString();
            		PreparedStatement stmt = conn.prepareStatement(query);
        			ResultSet rs = stmt.executeQuery();
        			Map<String, Object> assocMap = null;
        			while (rs.next()) {
        				assocMap = getEntityFromResultSet(rs, false);
        			}
            		resultsMap.put("associate", assocMap);
            	}
            	if (columnName.equals("card_id")) {
            		String query = "SELECT * FROM card_info WHERE card_id = " + object.toString();
            		PreparedStatement stmt = conn.prepareStatement(query);
        			ResultSet rs = stmt.executeQuery();
        			Map<String, Object> cardMap = null;
        			while (rs.next()) {
        				cardMap = getEntityFromResultSet(rs, false);
        			}
            		resultsMap.put("card", cardMap);
            	}
            }
        }
        return resultsMap;
    }
    
    /**
     * This methods checks the users credentials to see if they are authorized to use the application
     * @param userEmail The user's unique email address
     * @param pwd The user's password
     * @return Response This returns a JSON string with either the success or error message
     */
    @POST
    @Path("users/login")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_JSON)
    public Response loginUser(@FormParam("user_email") String userEmail, @FormParam("password") String pwd) {
    	// Checking parameters were sent correctly
    	if (userEmail == null || userEmail.trim().equals("") || pwd == null || pwd.trim().equals("")) {
    		return Response.status(400).entity("{\"status\":\"error\",\"status_code\":\"400\",\"error_msg\":\"There was an error passing in parameters. Both, user_email and password are required, please try again. Contact an administrator if the problem persists\"}").header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Headers", "Content-Type, Accept, X-Requested-With").build();
    	}
    	
		boolean connectionError = false;
		try {
			startConn();
		} catch (SQLException ex) {
			connectionError = true;
		}
		if (connectionError) {
			return Response.status(500).entity("{\"status\":\"error\",\"status_code\":\"500\",\"error_msg\":\"Could not connect to the database. Please verify the issue with a system administrator\"}").header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Headers", "Content-Type, Accept, X-Requested-With").build();
		}
		try {
			PreparedStatement stmt = conn.prepareStatement("SELECT user_email, password FROM user_info WHERE user_email = ?");
			stmt.setString(1, userEmail.trim());
			ResultSet rs = stmt.executeQuery();
			if (!rs.next()) {
				return Response.status(500).entity("{\"status\":\"error\",\"status_code\":\"403\",\"error_msg\":\"The user provided was not found in our database, make sure you have registered the correct email address\"}").header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Headers", "Content-Type, Accept, X-Requested-With").build();
			} else {
				String password = rs.getString("password");
				if (!pwd.trim().equals(password)) {
					return Response.status(500).entity("{\"status\":\"error\",\"status_code\":\"403\",\"error_msg\":\"The provided password does not match. Please try again\"}").header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Headers", "Content-Type, Accept, X-Requested-With").build();
				}
			}
			endConn();
			return Response.status(200).entity("{\"status\":\"success\",\"status_code\":\"200\",\"data\":\"Successful login\"}").header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Headers", "Content-Type, Accept, X-Requested-With").build();
		} catch (SQLException sqlex) {
			endConn();
			sqlex.printStackTrace();
			return Response.status(500).entity("{\"status\":\"error\",\"status_code\":\"500\",\"error_msg\":\"There was an error in the query to the database. Please verify your query or contact a system administrator for help with the issue\"}").header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Headers", "Content-Type, Accept, X-Requested-With").build();
		}
	}
    
    /**
     * This methods checks the users credentials to see if they are authorized to use the application
     * @param userId The user's unique email address or user_id from the database
     * @param file The profile picture to be uploaded
     * @return Response This returns a JSON string with either the success or error message
     */
    @POST
    @Path("users/{user_id}/uploadpic")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.APPLICATION_JSON)
    public Response uploadUserPic(@QueryParam("user_id") String userId, @FormDataParam("file") InputStream file, @FormDataParam("file") FormDataContentDisposition fileDetail) {
    	
    	String location = "/pics/uploaded/" + userId.trim() + "/" + fileDetail.getFileName();
    	try {
    		OutputStream out = new FileOutputStream(new File(location));
			int read = 0;
			byte[] bytes = new byte[1024];
			while ((read = file.read(bytes)) != -1) {
				out.write(bytes, 0, read);
			}
			out.flush();
			out.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
    	
		boolean connectionError = false;
		try {
			startConn();
		} catch (SQLException ex) {
			connectionError = true;
		}
		if (connectionError) {
			return Response.status(500).entity("{\"status\":\"error\",\"status_code\":\"500\",\"error_msg\":\"Could not connect to the database. Please verify the issue with a system administrator\"}").header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Headers", "Content-Type, Accept, X-Requested-With").build();
		}
		try {
			String query = "UPDATE user_info SET profile_pic_uri = ? WHERE";
			if (!isNumeric(userId)) {
				query = query + " user_email = ?";
			} else {
				query = query + " user_id = ?";
			}
			PreparedStatement stmt = conn.prepareStatement(query);
			stmt.setString(1, location.trim());
			if (!isNumeric(userId)) {
				stmt.setString(2, userId.trim());
			} else {
				stmt.setInt(2, Integer.parseInt(userId.trim()));
			}
			@SuppressWarnings("unused")
			int rn = stmt.executeUpdate();
			endConn();
			return Response.status(200).entity("{\"status\":\"success\",\"status_code\":\"200\",\"data\":\"Profile pic was uploaded successfully\"}").header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Headers", "Content-Type, Accept, X-Requested-With").build();
		} catch (SQLException sqlex) {
			endConn();
			sqlex.printStackTrace();
			return Response.status(500).entity("{\"status\":\"error\",\"status_code\":\"500\",\"error_msg\":\"There was an error trying to upload the profile picture. Please try again, and in case of continuous failure, contact a system administrator for help with the issue\"}").header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Headers", "Content-Type, Accept, X-Requested-With").build();
		}
	}
    
  /**
     * This method queries user information on the database (user_info). It brings all
	 * records in the uesr_info database.
	 * @return Response This returns a json string with the user's information on success,
	 * or an error message on error.
	 */
  @GET
  @Path("users")
  @Consumes(MediaType.TEXT_HTML)
  @Produces(MediaType.APPLICATION_JSON)
  public Response getAllUserInformation() {
	  System.out.println("getAll");
		boolean connectionError = false;
		try {
			startConn();
		} catch (SQLException ex) {
			connectionError = true;
		}
		if (connectionError) {
			return Response.status(500).entity("{\"status\":\"error\",\"status_code\":\"500\",\"error_msg\":\"Could not connect to the database. Please verify the issue with a system administrator\"}").header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Headers", "Content-Type, Accept, X-Requested-With").build();
		}
		String query = "SELECT * FROM user_info";
		try {
			PreparedStatement stmt = conn.prepareStatement(query);
			ResultSet rs = stmt.executeQuery();
			List<Map<String, Object>> listOfMaps = null;
			listOfMaps = getEntitiesFromResultSet(rs, false);
			Gson gson = new GsonBuilder().serializeNulls().setDateFormat("dd MMM yyyy HH:mm:ss").create();
			String res = gson.toJson(listOfMaps);
			endConn();
			return Response.status(200).entity("{\"status\":\"success\",\"status_code\":\"200\",\"data\":" + res + "}").header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Headers", "Content-Type, Accept, X-Requested-With").build();
		} catch (SQLException sqlex) {
			endConn();
			sqlex.printStackTrace();
			return Response.status(500).entity("{\"status\":\"error\",\"status_code\":\"500\",\"error_msg\":\"There was an error in the query to the database. Please verify your query or contact a system administrator for help with the issue\"}").header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Headers", "Content-Type, Accept, X-Requested-With").build();
		}
	}
    
    /**
	   * This method queries user information on the database (user_info). It queries
	   * a single record at a time. It can filter by either user_id or the user's email
	   * address, which should be unique.
	   * @param userId The email/user_id used to narrow the search
	   * @return Response This returns a json string with the user's information on success,
	   * or an error message on error.
	   */
    @GET
    @Path("users/{user_id}")
    @Consumes(MediaType.TEXT_HTML)
    @Produces(MediaType.APPLICATION_JSON)
    public Response getUserInformation(@PathParam("user_id") String userId) {
    	System.out.println("getParticular");
		boolean connectionError = false;
		try {
			startConn();
		} catch (SQLException ex) {
			connectionError = true;
		}
		if (connectionError) {
			return Response.status(500).entity("{\"status\":\"error\",\"status_code\":\"500\",\"error_msg\":\"Could not connect to the database. Please verify the issue with a system administrator\"}").header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Headers", "Content-Type, Accept, X-Requested-With").build();
		}
		String query = "SELECT * FROM user_info WHERE";
		if (!isNumeric(userId)) {
			query = query + " user_email = '" + userId + "'";
		} else {
			query = query + " user_id = " + userId;
		}
		try {
			PreparedStatement stmt = conn.prepareStatement(query);
			ResultSet rs = stmt.executeQuery();
			List<Map<String, Object>> listOfMaps = null;
			listOfMaps = getEntitiesFromResultSet(rs, false);
			Gson gson = new GsonBuilder().serializeNulls().setDateFormat("dd MMM yyyy HH:mm:ss").create();
			String res = gson.toJson(listOfMaps);
			endConn();
			return Response.status(200).entity("{\"status\":\"success\",\"status_code\":\"200\",\"data\":" + res + "}").header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Headers", "Content-Type, Accept, X-Requested-With").build();
		} catch (SQLException sqlex) {
			endConn();
			sqlex.printStackTrace();
			return Response.status(500).entity("{\"status\":\"error\",\"status_code\":\"500\",\"error_msg\":\"There was an error in the query to the database. Please verify your query or contact a system administrator for help with the issue\"}").header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Headers", "Content-Type, Accept, X-Requested-With").build();
		}
	}
    
    /**
	   * This method creates a new user record in the database (user_info)
	   * @param userEmail The user's email address (username)
	   * @param lastName1 The user's main (first) last name
	   * @param lastName2 The user's second last name (if any)
	   * @param names The user's first and middle names
	   * @param dobDay The user's day of birth
	   * @param dobMonth The user's month of birth
	   * @param dobYear The user's year of birth
	   * @param cellPhone The user's cellphone number
	   * @param pwd The user's account password
	   * @param profilePicUri Uri for the user's profile pic (thorugh Facebook or Google)
	   * @return Response This returns a json string with the status of the transaction
	   */
    @POST
    @Path("users")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_JSON)
    public Response createUser(@FormParam("user_email") String userEmail, @FormParam("last_name_1") String lastName1, @FormParam("last_name_2") String lastName2, @FormParam("names") String names, @FormParam("dob_day") String dobDay, @FormParam("dob_month") String dobMonth, @FormParam("dob_year") String dobYear, @FormParam("cell_phone") String cellPhone, @FormParam("password") String pwd, @FormParam("profile_pic_uri") String profilePicUri) {
    	// Checking parameters were sent correctly
    	if (userEmail == null || userEmail.trim().equals("") || pwd == null || pwd.trim().equals("")) {
    		return Response.status(400).entity("{\"status\":\"error\",\"status_code\":\"400\",\"error_msg\":\"There was an error passing in parameters. Please try again. Contact an administrator if the problem persists\"}").header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Headers", "Content-Type, Accept, X-Requested-With").build();
    	}
    	
		boolean connectionError = false;
		try {
			startConn();
		} catch (SQLException ex) {
			connectionError = true;
		}
		if (connectionError) {
			return Response.status(500).entity("{\"status\":\"error\",\"status_code\":\"500\",\"error_msg\":\"Could not connect to the database. Please verify the issue with a system administrator\"}").header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Headers", "Content-Type, Accept, X-Requested-With").build();
		}
		OpenpayAPI opApi = new OpenpayAPI("https://sandbox-api.openpay.mx", "sk_6c0df178859c4ab5af465cfbfc3d9df4", "mtosulk5cfmy071mhzrr");
		Customer customer;
		try {
			customer = opApi.customers().create(new Customer().name(names).lastName(lastName1).email(userEmail).phoneNumber(cellPhone).requiresAccount(false));
		} catch (OpenpayServiceException opex) {
			opex.printStackTrace();
			return Response.status(500).entity("{\"status\":\"error\",\"status_code\":\"500\",\"error_msg\":\"There was an issue creating the client in Openpay. " +  opex.getDescription() + ". Please verify the issue with a system administrator\"}").header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Headers", "Content-Type, Accept, X-Requested-With").build();
		} catch (ServiceUnavailableException suex) {
			suex.printStackTrace();
			return Response.status(500).entity("{\"status\":\"error\",\"status_code\":\"500\",\"error_msg\":\"Could not connect to the Openpay to create the client. Please verify the issue with a system administrator\"}").header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Headers", "Content-Type, Accept, X-Requested-With").build();
		}
		try {
			PreparedStatement stmt = conn.prepareStatement("INSERT INTO user_info (user_email,last_name_1,last_name_2,names,dob_day,dob_month,dob_year,cell_phone,password,profile_pic_uri,openpay_client_id) VALUES (?,?,?,?,?,?,?,?,?,?,?)");
			stmt.setString(1, userEmail.trim());
			if (lastName1 == null) {
				stmt.setNull(2, java.sql.Types.VARCHAR);
			} else {
				stmt.setString(2, lastName1.trim());
			}
			if (lastName2 == null) {
				stmt.setNull(3, java.sql.Types.VARCHAR);
			} else {
				stmt.setString(3, lastName2.trim());
			}
			if (names == null) {
				stmt.setNull(4, java.sql.Types.VARCHAR);
			} else {
				stmt.setString(4, names.trim());
			}
			if (dobDay == null) {
				stmt.setNull(5, java.sql.Types.VARCHAR);
			} else {
				stmt.setString(5, dobDay.trim());
			}
			if (dobMonth == null) {
				stmt.setNull(6, java.sql.Types.VARCHAR);
			} else {
				stmt.setString(6, dobMonth.trim());
			}
			if (dobYear == null) {
				stmt.setNull(7, java.sql.Types.VARCHAR);
			} else {
				stmt.setString(7, dobYear.trim());
			}
			if (cellPhone == null) {
				stmt.setNull(8, java.sql.Types.VARCHAR);
			} else {
				stmt.setString(8, cellPhone.trim());
			}
			stmt.setString(9, pwd.trim());
			if (profilePicUri == null) {
				stmt.setNull(10, java.sql.Types.VARCHAR);
			} else {
				stmt.setString(10, profilePicUri.trim());
			}
			stmt.setString(11, customer.getId());
			int rn = stmt.executeUpdate();
			endConn();
			return Response.status(200).entity("{\"status\":\"success\",\"status_code\":\"200\",\"updated_rows\":\"" + rn + "\"}").header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Headers", "Content-Type, Accept, X-Requested-With").build();
		} catch (SQLException sqlex) {
			endConn();
			sqlex.printStackTrace();
			return Response.status(500).entity("{\"status\":\"error\",\"status_code\":\"500\",\"error_msg\":\"There was an error in the query to the database. Please verify your query or contact a system administrator for help with the issue\"}").header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Headers", "Content-Type, Accept, X-Requested-With").build();
		}
	}
    
    /**
	   * This method updates a user record in the database (user_info)
	   * @param userId The user_id used to find the record to update
	   * @param userEmail The user's email address (username)
	   * @param lastName1 The user's main (first) last name. It is updatable
	   * @param lastName2 The user's second last name (if any). It is updatable
	   * @param names The user's first and middle names. It is updatable
	   * @param dobDay The user's day of birth. It is updatable
	   * @param dobMonth The user's month of birth. It is updatable
	   * @param dobYear The user's year of birth. It is updatable
	   * @param cellPhone The user's cellphone number. It is updatable
	   * @param pwd The user's account password. It is updatable
	   * @param profileUriPic Uri for the user's profile pic (thorugh Facebook or Google). It is updatable
	   * @param isActive Flag that checks whether the user is active. It is updatable
	   * @param isLoggedIn Flag that checks whether the user is currently logged in. It is updatable
	   * @param lastLoginDate The user's last login date. It is updatable
	   * @return Response This returns a json string with the status of the transaction
	   */
    @PUT
    @Path("users/{user_id}")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_JSON)
    public Response updateUser(@PathParam("user_id") String userId, @FormParam("last_name_1") String lastName1, @FormParam("last_name_2") String lastName2, @FormParam("names") String names, @FormParam("dob_day") String dobDay, @FormParam("dob_month") String dobMonth, @FormParam("dob_year") String dobYear, @FormParam("cell_phone") String cellPhone, @FormParam("password") String pwd, @FormParam("profile_pic_uri") String profilePicUri, @FormParam("is_active") String isActive, @FormParam("is_logged_in") String isLoggedIn, @FormParam("last_login_date") String lastLoginDate) {
    	// Checking parameters were sent correctly
    	if ((pwd == null || pwd.trim().equals("")) && (isActive == null || (!isActive.trim().equals("N") && !isActive.trim().equals("Y"))) && (lastName1 == null || lastName1.trim().equals("")) && lastName2 == null && (names == null || names.trim().equals("")) && (dobDay == null || dobDay.trim().equals("")) && (dobMonth == null || dobMonth.trim().equals("")) && (dobYear == null || dobYear.trim().equals("")) && cellPhone == null && profilePicUri == null && (isLoggedIn == null || (!isLoggedIn.trim().equals("N") && !isLoggedIn.trim().equals("Y"))) && (lastLoginDate == null || lastLoginDate.trim().equals(""))) {
    		return Response.status(400).entity("{\"status\":\"error\",\"status_code\":\"400\",\"error_msg\":\"There was an error passing in parameters. Please try again. Contact an administrator if the problem persists\"}").header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Headers", "Content-Type, Accept, X-Requested-With").build();
    	}
    	
    	boolean isPwd = false;
		boolean isStatus = false;
		boolean isLastName1 = false;
		boolean isLastName2 = false;
		boolean isNames = false;
		boolean isDobDay = false;
		boolean isDobMonth = false;
		boolean isDobYear = false;
		boolean isCellPhone = false;
		boolean isProfilePicUri = false;
		boolean isLoginChange = false;
		boolean isLastLoginDate = false;
		int flags = 0;
    	
		boolean connectionError = false;
		try {
			startConn();
		} catch (SQLException ex) {
			connectionError = true;
		}
		if (connectionError) {
			return Response.status(500).entity("{\"status\":\"error\",\"status_code\":\"500\",\"error_msg\":\"Could not connect to the database. Please verify the issue with a system administrator\"}").header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Headers", "Content-Type, Accept, X-Requested-With").build();
		} 
		
		if (pwd != null && !pwd.trim().equals("")) {
			isPwd = true;
			flags++;
		}
		if (isActive != null && (isActive.trim().equals("N") || isActive.trim().equals("Y"))) {
			isStatus = true;
			flags++;
		}
		if (lastName1 != null && !lastName1.trim().equals("")) {
			isLastName1 = true;
			flags++;
		}
		if (lastName2 != null) {
			isLastName2 = true;
			flags++;
		}
		if (names != null && !names.trim().equals("")) {
			isNames = true;
			flags++;
		}
		if (dobDay != null && !dobDay.trim().equals("")) {
			isDobDay = true;
			flags++;
		}
		if (dobMonth != null && !dobMonth.trim().equals("")) {
			isDobMonth = true;
			flags++;
		}
		if (dobYear != null && !dobYear.trim().equals("")) {
			isDobYear = true;
			flags++;
		}
		if (cellPhone != null) {
			isCellPhone = true;
			flags++;
		}
		if (profilePicUri != null) {
			isProfilePicUri = true;
			flags++;
		}
		if (isLoggedIn != null && !isLoggedIn.trim().equals("")) {
			isLoginChange = true;
			flags++;
		}
		if (lastLoginDate != null && !lastLoginDate.trim().equals("")) {
			isLastLoginDate = true;
			flags++;
		}
		
		String uQuery = "UPDATE user_info SET ";
		if (isPwd) {
			uQuery = uQuery + "password = ?,";
		}
		if (isStatus) {
			uQuery = uQuery + "is_active = ?,";
		}
		if (isLastName1) {
			uQuery = uQuery + "last_name_1 = ?,";
		}
		if (isLastName2) {
			uQuery = uQuery + "last_name_2 = ?,";
		}
		if (isNames) {
			uQuery = uQuery + "names = ?,";
		}
		if (isDobDay) {
			uQuery = uQuery + "dob_day = ?,";
		}
		if (isDobMonth) {
			uQuery = uQuery + "dob_month = ?,";
		}
		if (isDobYear) {
			uQuery = uQuery + "dob_year = ?,";
		}
		if (isCellPhone) {
			uQuery = uQuery + "cell_phone = ?,";
		}
		if (isProfilePicUri) {
			uQuery = uQuery + "profile_pic_uri = ?,";
		}
		if (isLoginChange) {
			uQuery = uQuery + "is_logged_in = ?,";
		}
		if (isLastLoginDate) {
			uQuery = uQuery + "last_login_date = ?,";
		}
		if (!isNumeric(userId)) {
			uQuery = uQuery + "last_update_date = CURRENT_TIMESTAMP WHERE user_email = ?";
		} else {
			uQuery = uQuery + "last_update_date = CURRENT_TIMESTAMP WHERE user_id = ?";
		}
		try {
			PreparedStatement stmt = conn.prepareStatement(uQuery);
			for (int i = 1; i <= flags; i++) {
				if (isPwd) {
					stmt.setString(i, pwd.trim());
					isPwd = false;
					continue;
				}
				if (isStatus) {
					stmt.setString(i, isActive.trim());
					isStatus = false;
					continue;
				}
				if (isLastName1) {
					stmt.setString(i, lastName1.trim());
					isLastName1 = false;
					continue;
				}
				if (isLastName2) {
					stmt.setString(i, lastName2.trim());
					isLastName2 = false;
					continue;
				}
				if (isLastName2) {
					stmt.setString(i, lastName2.trim());
					isLastName2 = false;
					continue;
				}
				if (isNames) {
					stmt.setString(i, names.trim());
					isNames = false;
					continue;
				}
				if (isDobDay) {
					stmt.setString(i, dobDay.trim());
					isDobDay = false;
					continue;
				}
				if (isDobMonth) {
					stmt.setString(i, dobMonth.trim());
					isDobMonth = false;
					continue;
				}
				if (isDobYear) {
					stmt.setString(i, dobYear.trim());
					isDobYear = false;
					continue;
				}
				if (isCellPhone) {
					stmt.setString(i, cellPhone.trim());
					isCellPhone = false;
					continue;
				}
				if (isProfilePicUri) {
					stmt.setString(i, profilePicUri.trim());
					isProfilePicUri = false;
					continue;
				}
				if (isLoginChange) {
					stmt.setString(i, isLoggedIn.trim());
					isLoginChange = false;
					continue;
				}
				if (isLastLoginDate) {
					Timestamp ts = Timestamp.valueOf(lastLoginDate);
					stmt.setTimestamp(i, ts);
					isLastLoginDate = false;
					continue;
				}
			}
			stmt.setString(flags + 1, userId.trim());
			int rn = stmt.executeUpdate();
			endConn();
			return Response.status(200).entity("{\"status\":\"success\",\"status_code\":\"200\",\"updated_rows\":\"" + rn + "\"}").header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Headers", "Content-Type, Accept, X-Requested-With").build();
		} catch (SQLException sqlex) {
			endConn();
			sqlex.printStackTrace();
			return Response.status(500).entity("{\"status\":\"error\",\"status_code\":\"500\",\"error_msg\":\"There was an error in the query to the database. Please verify your query or contact a system administrator for help with the issue\"}").header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Headers", "Content-Type, Accept, X-Requested-With").build();
		}
	}
    
    /**
	   * This method deletes a record of user information from the database (user_info).
	   * @param userId The user_id used to narrow the search
	   * @return Response This returns a json string with the status of the transaction
	   */
    @DELETE
    @Path("users/{user_id}")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_JSON)
    public Response deleteUser(@PathParam("user_id") String userId) {
		boolean connectionError = false;
		try {
			startConn();
		} catch (SQLException ex) {
			connectionError = true;
		}
		if (connectionError) {
			return Response.status(500).entity("{\"status\":\"error\",\"status_code\":\"500\",\"error_msg\":\"Could not connect to the database. Please verify the issue with a system administrator\"}").header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Headers", "Content-Type, Accept, X-Requested-With").build();
		}
		String sQuery = "SELECT openpay_client_id, user_id FROM user_info WHERE";
		String cQuery = "SELECT openpay_card_id FROM card_info WHERE user_id = ?";
		String dcQuery = "UPDATE card_info SET isActive = ? WHERE user_id = ?";
		String dQuery = "UPDATE user_info SET isActive = ? WHERE";
		if (!isNumeric(userId)) {
			sQuery = sQuery + " user_email = ?";
			dQuery = dQuery + " user_email = ?";
		} else {
			sQuery = sQuery + " user_id = ?";
			dQuery = dQuery + " user_id = ?";
		}
		try {
			// Getting the user's Openpay client id
			PreparedStatement sStmt = conn.prepareStatement(sQuery);
			if (!isNumeric(userId)) {
				sStmt.setString(1, userId.trim());
			} else {
				sStmt.setInt(1, Integer.parseInt(userId.trim()));
			}
			ResultSet sRs = sStmt.executeQuery();
			sRs.next();
			int uId = sRs.getInt("user_id");
			String opId = sRs.getString("openpay_client_id"); 
			
			// Getting all cards associated to user
			PreparedStatement cStmt = conn.prepareStatement(cQuery);
			cStmt.setInt(1, uId);
			ResultSet cRs = cStmt.executeQuery();
			
			// Deleting data in Open Pay
			OpenpayAPI opApi = new OpenpayAPI("https://sandbox-api.openpay.mx", "sk_6c0df178859c4ab5af465cfbfc3d9df4", "mtosulk5cfmy071mhzrr");
			try {
				while(cRs.next()) {
					opApi.cards().delete(opId, cRs.getString("openpay_card_id"));
				}
				opApi.customers().delete(opId);
			} catch (OpenpayServiceException opex) {
				endConn();
				opex.printStackTrace();
				return Response.status(500).entity("{\"status\":\"error\",\"status_code\":\"500\",\"error_msg\":\"There was an issue completely deleting all client info from Openpay. " +  opex.getDescription() + ". Please contact a system administrator for help with the issue\"}").header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Headers", "Content-Type, Accept, X-Requested-With").build();
			} catch (ServiceUnavailableException suex) {
				endConn();
				suex.printStackTrace();
				return Response.status(500).entity("{\"status\":\"error\",\"status_code\":\"500\",\"error_msg\":\"There was an error connecting to Openpay. Please contact a system administrator for help with the issue\"}").header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Headers", "Content-Type, Accept, X-Requested-With").build();
			}
			System.out.println("Openpay information deleted for user_id " + userId.trim());
			
			// Deleting card data in our databases
			PreparedStatement dcStmt = conn.prepareStatement(dcQuery);
			dcStmt.setString(1, "N");
			dcStmt.setInt(2, uId);
			int dcRn = dcStmt.executeUpdate(); 
			
			// Deleting user data in our databases
			PreparedStatement dStmt = conn.prepareStatement(dQuery);
			dStmt.setString(1, "N");
			if (!isNumeric(userId)) {
				dStmt.setString(2, userId.trim());
			} else {
				dStmt.setInt(2, Integer.parseInt(userId.trim()));
			}
			int dRn = dStmt.executeUpdate();
			int rn = dcRn + dRn;
			endConn();
			System.out.println("aNet database info deactivated for user_id " + userId.trim());
			return Response.status(200).entity("{\"status\":\"success\",\"status_code\":\"200\",\"updated_rows\":\"" + rn + "\"}").header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Headers", "Content-Type, Accept, X-Requested-With").build();
		} catch (SQLException sqlex) {
			endConn();
			sqlex.printStackTrace();
			return Response.status(500).entity("{\"status\":\"error\",\"status_code\":\"500\",\"error_msg\":\"There was an error in the query to the database. Please verify your query or contact a system administrator for help with the issue\"}").header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Headers", "Content-Type, Accept, X-Requested-With").build();
		}
	}
    
    /**
	   * This method queries card information on the database (card_info) for a particular user.
	   * It queries all cards belonging to one user. It can bring only currently active cards,
	   * or it can bring all cards, even those already deactivated 
	   * @param userId The email/user_id used to narrow the search
	   * @param history Flag that will allow inactive cards to show up in the results
	   * @return Response This returns a json string with the user's card information on success,
	   * or an error message on error.
	   */
    @GET
    @Path("users/{user_id}/cards")
    @Consumes(MediaType.TEXT_HTML)
    @Produces(MediaType.APPLICATION_JSON)
    public Response getAllUserCardInfo(@PathParam("user_id") String userId, @DefaultValue("false") @QueryParam("history") String history) {
		boolean connectionError = false;
		try {
			startConn();
		} catch (SQLException ex) {
			connectionError = true;
		}
		if (connectionError) {
			return Response.status(500).entity("{\"status\":\"error\",\"status_code\":\"500\",\"error_msg\":\"Could not connect to the database. Please verify the issue with a system administrator\"}").header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Headers", "Content-Type, Accept, X-Requested-With").build();
		}
		String sQuery = "SELECT user_id FROM user_info WHERE";
		if (!isNumeric(userId)) {
			sQuery = sQuery + " user_email = '" + userId + "'"; 
		} else {
			sQuery = sQuery + " user_id = " + userId;
		}
		try {
			// Getting user id to use to filter out the cards in the card_info table
			PreparedStatement sStmt = conn.prepareStatement(sQuery);
			ResultSet sRs = sStmt.executeQuery();
			sRs.next();
			int uId = sRs.getInt("user_id");
		
			// Getting all cards for a particular user
			String query = "SELECT * FROM card_info WHERE user_id = " + uId;
			if (!history.equals("true")) {
				query = query + " AND is_active = 'Y'";
			}
			PreparedStatement stmt = conn.prepareStatement(query);
			ResultSet rs = stmt.executeQuery();
			List<Map<String, Object>> listOfMaps = null;
			listOfMaps = getEntitiesFromResultSet(rs, false);
			Gson gson = new GsonBuilder().serializeNulls().setDateFormat("dd MMM yyyy HH:mm:ss").create();
			String res = gson.toJson(listOfMaps);
			endConn();
			return Response.status(200).entity("{\"status\":\"success\",\"status_code\":\"200\",\"data\":" + res + "}").header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Headers", "Content-Type, Accept, X-Requested-With").build();
		} catch (SQLException sqlex) {
			endConn();
			sqlex.printStackTrace();
			return Response.status(500).entity("{\"status\":\"error\",\"status_code\":\"500\",\"error_msg\":\"There was an error in the query to the database. Please verify your query or contact a system administrator for help with the issue\"}").header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Headers", "Content-Type, Accept, X-Requested-With").build();
		}
	}
	
    /**
	   * This method queries card information on the database (card_info) for a particular user.
	   * It queries a single record at a time. It can filter by using the unique card_id
	   * @param userId The email/user_id used to narrow the search
	   * @param cardId The card_id used to narrow the search further
	   * @return Response This returns a json string with the user's card information on success,
	   * or an error message on error.
	   */
	@GET
    @Path("users/{user_id}/cards/{card_id}")
    @Consumes(MediaType.TEXT_HTML)
    @Produces(MediaType.APPLICATION_JSON)
    public Response getUserCardInfo(@PathParam("user_id") String userId, @PathParam("card_id") String cardId) {
		boolean connectionError = false;
		try {
			startConn();
		} catch (SQLException ex) {
			connectionError = true;
		}
		if (connectionError) {
			return Response.status(500).entity("{\"status\":\"error\",\"status_code\":\"500\",\"error_msg\":\"Could not connect to the database. Please verify the issue with a system administrator\"}").header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Headers", "Content-Type, Accept, X-Requested-With").build();
		} 
		try {
			// Getting card record by card_id
			PreparedStatement stmt = conn.prepareStatement("SELECT * FROM card_info WHERE card_id = " + cardId.trim());
			ResultSet rs = stmt.executeQuery();
			List<Map<String, Object>> listOfMaps = null;
			listOfMaps = getEntitiesFromResultSet(rs, false);
			Gson gson = new GsonBuilder().serializeNulls().setDateFormat("dd MMM yyyy HH:mm:ss").create();
			String res = gson.toJson(listOfMaps);
			endConn();
			return Response.status(200).entity("{\"status\":\"success\",\"status_code\":\"200\",\"data\":" + res + "}").header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Headers", "Content-Type, Accept, X-Requested-With").build();
		} catch (SQLException sqlex) {
			endConn();
			sqlex.printStackTrace();
			return Response.status(500).entity("{\"status\":\"error\",\"status_code\":\"500\",\"error_msg\":\"There was an error in the query to the database. Please verify your query or contact a system administrator for help with the issue\"}").header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Headers", "Content-Type, Accept, X-Requested-With").build();
		}
	}
	
	/**
	   * This method creates a new card record in the database (card_info)
	   * @param userId The user_id used to know to which user to link the card
	   * @param cardNum The card's number
	   * @param holderName The name of the card's holder
	   * @param expYear The expiration year of the card
	   * @param expMonth The expiration month of the card
	   * @param cvv2 The cvv2 code for the card
	   * @param line1 The first line for the address where the card statements go
	   * @param line2 The second line for the address where the card statements go
	   * @param city The city linked to the address where the card statements go
	   * @param state The state linked to the address where the card statements go
	   * @param country The country linked to the address where the card statements go
	   * @param postalCode The postal code linked to the address where the card statements go
 	   * @return Response This returns a json string with the status of the transaction
	   */
	@POST
    @Path("users/{user_id}/cards")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_JSON)
    public Response createCard(@PathParam("user_id") String userId, @FormParam("token_id") String tokenId, @FormParam("device_session_id") String deviceSessionId) {
		// Checking parameters were sent correctly
    	if (tokenId == null || deviceSessionId == null || tokenId.trim().equals("") || deviceSessionId.trim().equals("")) {
    		return Response.status(400).entity("{\"status\":\"error\",\"status_code\":\"400\",\"error_msg\":\"There was an error passing in parameters. Please try again. Contact an administrator if the problem persists\"}").header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Headers", "Content-Type, Accept, X-Requested-With").build();
    	}
    	
		boolean connectionError = false;
		try {
			startConn();
		} catch (SQLException ex) {
			connectionError = true;
		}
		if (connectionError) {
			return Response.status(500).entity("{\"status\":\"error\",\"status_code\":\"500\",\"error_msg\":\"Could not connect to the database. Please verify the issue with a system administrator\"}").header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Headers", "Content-Type, Accept, X-Requested-With").build();
		} 
		try {
			// Getting Openpay client id to create card
			String sQuery = "SELECT openpay_client_id, user_id FROM user_info WHERE";
			if (!isNumeric(userId)) {
				sQuery = sQuery + " user_email = ?";
			} else {
				sQuery = sQuery + " user_id = ?";
			}
			PreparedStatement sStmt = conn.prepareStatement(sQuery);
			if (!isNumeric(userId)) {
				sStmt.setString(1, userId.trim());
			} else {
				sStmt.setInt(1, Integer.parseInt(userId.trim()));
			}
			ResultSet sRs = sStmt.executeQuery();
			sRs.next();
			String customerId = sRs.getString("openpay_client_id");
			int uId = sRs.getInt("user_id");

			// Creating card in Openpay
			OpenpayAPI opApi = new OpenpayAPI("https://sandbox-api.openpay.mx", "sk_6c0df178859c4ab5af465cfbfc3d9df4", "mtosulk5cfmy071mhzrr");
			Card card = new Card();
			try {
				card.tokenId(tokenId);
				card.setDeviceSessionId(deviceSessionId);
				card = opApi.cards().create(customerId, card);
			} catch (OpenpayServiceException opex) {
				endConn();
				opex.printStackTrace();
				return Response.status(500).entity("{\"status\":\"error\",\"status_code\":\"500\",\"error_msg\":\"There was an issue creating a card for the client in Openpay. " +  opex.getDescription() + ". Please contact a system administrator for help with the issue\"}").header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Headers", "Content-Type, Accept, X-Requested-With").build();
			} catch (ServiceUnavailableException suex) {
				endConn();
				suex.printStackTrace();
				return Response.status(500).entity("{\"status\":\"error\",\"status_code\":\"500\",\"error_msg\":\"There was an error trying to access Openpay. Please contact a system administrator for help with the issue\"}").header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Headers", "Content-Type, Accept, X-Requested-With").build();
			}
			
			// Inserting data into our database
			PreparedStatement stmt = conn.prepareStatement("INSERT INTO card_info (user_id,card_number,holder_name,expiration_year,expiration_month,openpay_card_id,type,brand,bank_name,bank_code,allows_charges) VALUES (?,?,?,?,?,?,?,?,?,?,?)");
			stmt.setInt(1, uId);
			stmt.setString(2, card.getCardNumber());
			stmt.setString(3, card.getHolderName());
			stmt.setString(4, card.getExpirationYear());
			stmt.setString(5, card.getExpirationMonth());
			stmt.setString(6, card.getId());
			stmt.setString(7, card.getType());
			stmt.setString(8, card.getBrand());
			stmt.setString(9, card.getBankName());
			stmt.setString(10, card.getBankCode());
			if (card.getAllowsCharges()) {
            	stmt.setString(11, "Y");
            } else {
            	stmt.setString(11, "N");
            }
			int rn = stmt.executeUpdate();
			endConn();
			return Response.status(200).entity("{\"status\":\"success\",\"status_code\":\"200\",\"updated_rows\":\"" + rn + "\"}").header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Headers", "Content-Type, Accept, X-Requested-With").build();
		} catch (SQLException sqlex) {
			endConn();
			sqlex.printStackTrace();
			return Response.status(500).entity("{\"status\":\"error\",\"status_code\":\"500\",\"error_msg\":\"There was an error in the query to the database. Please verify your query or contact a system administrator for help with the issue\"}").header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Headers", "Content-Type, Accept, X-Requested-With").build();
		}
	}
    
	/**
	   * This method updates an existing card record in the database (card_info)
	   * @param cardId The card_id to fetch the exact record to be updated
	   * @param isActive Flag that indicates whether the card is active
	   * @return Response This returns a json string with the status of the transaction
	   */
    @PUT
    @Path("users/{user_id}/cards/{card_id}")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_JSON)
    public Response updateCard(@PathParam("card_id") String cardId, @PathParam("is_active") String isActive) {
    	// Checking parameters were sent correctly
    	if (cardId == null || cardId.trim().equals("") || isActive == null || (!isActive.trim().equals("N") && !isActive.trim().equals("Y"))) {
    		return Response.status(400).entity("{\"status\":\"error\",\"status_code\":\"400\",\"error_msg\":\"There was an error passing in parameters. Please try again. Contact an administrator if the problem persists\"}").header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Headers", "Content-Type, Accept, X-Requested-With").build();
    	}
    	
		boolean connectionError = false;
		try {
			startConn();
		} catch (SQLException ex) {
			connectionError = true;
		}
		if (connectionError) {
			return Response.status(500).entity("{\"status\":\"error\",\"status_code\":\"500\",\"error_msg\":\"Could not connect to the database. Please verify the issue with a system administrator\"}").header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Headers", "Content-Type, Accept, X-Requested-With").build();
		} 
		try {
			PreparedStatement stmt = conn.prepareStatement("UPDATE card_info SET is_active = ?, last_update_date = CURRENT_TIMESTAMP WHERE card_id = ?");
			stmt.setString(1, isActive.trim());
			stmt.setInt(2, Integer.parseInt(cardId.trim()));
			int rn = stmt.executeUpdate();
			endConn();
			return Response.status(200).entity("{\"status\":\"success\",\"status_code\":\"200\",\"updated_rows\":\"" + rn + "\"}").header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Headers", "Content-Type, Accept, X-Requested-With").build();
		} catch (SQLException sqlex) {
			endConn();
			sqlex.printStackTrace();
			return Response.status(500).entity("{\"status\":\"error\",\"status_code\":\"500\",\"error_msg\":\"There was an error in the query to the database. Please verify your query or contact a system administrator for help with the issue\"}").header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Headers", "Content-Type, Accept, X-Requested-With").build();
		}
	}
    
    /**
	   * This method deletes a card record for a user from the database (card_info).
	   * @param userId The user_id used to narrow the search
	   * @param cardId the card_id to be deleted
	   * @return Response This returns a json string with the status of the transaction
	   */
    @DELETE
    @Path("users/{user_id}/cards/{card_id}")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_JSON)
    public Response deleteCard(@PathParam("user_id") String userId, @PathParam("card_id") String cardId) {
		boolean connectionError = false;
		try {
			startConn();
		} catch (SQLException ex) {
			connectionError = true;
		}
		if (connectionError) {
			return Response.status(500).entity("{\"status\":\"error\",\"status_code\":\"500\",\"error_msg\":\"Could not connect to the database. Please verify the issue with a system administrator\"}").header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Headers", "Content-Type, Accept, X-Requested-With").build();
		} 
		try {
			// Get the Openpay client Id to delete the card in Openpay
			String sQuery = "SELECT openpay_client_id, user_id FROM user_info WHERE";
			if (!isNumeric(userId)) {
				sQuery = sQuery + " user_email = ?";
			} else {
				sQuery = sQuery + " user_id = ?";
			}
			PreparedStatement sStmt = conn.prepareStatement(sQuery);
			if (!isNumeric(userId)) {
				sStmt.setString(1, userId.trim());
			} else {
				sStmt.setInt(1, Integer.parseInt(userId.trim()));
			}
			ResultSet sRs = sStmt.executeQuery();
			sRs.next();
			String opId = sRs.getString("openpay_client_id");
			
			// Get the Openpay card Id to delete the card in Openpay
			PreparedStatement cStmt = conn.prepareStatement("SELECT openpay_card_id FROM card_info WHERE card_id = ?");
			cStmt.setInt(1, Integer.parseInt(cardId.trim()));
			ResultSet cRs = cStmt.executeQuery();
			cRs.next();
			String cOpId = cRs.getString("openpay_card_id");
			
			// Delete card in Openpay
			OpenpayAPI opApi = new OpenpayAPI("https://sandbox-api.openpay.mx", "sk_6c0df178859c4ab5af465cfbfc3d9df4", "mtosulk5cfmy071mhzrr");
			try {
				opApi.cards().delete(opId, cOpId);
			} catch (ServiceUnavailableException suex) {
				endConn();
				suex.printStackTrace();
				return Response.status(500).entity("{\"status\":\"error\",\"status_code\":\"500\",\"error_msg\":\"There was an issue connecting to Openpay. Please contact a system administrator for help with the issue\"}").header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Headers", "Content-Type, Accept, X-Requested-With").build();
			} catch (OpenpayServiceException opex) {
				endConn();
				opex.printStackTrace();
				return Response.status(500).entity("{\"status\":\"error\",\"status_code\":\"500\",\"error_msg\":\"There was an issue trying to delete the card requested from Openpay. " +  opex.getDescription() + ". Please contact a system administrator for help with the issue\"}").header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Headers", "Content-Type, Accept, X-Requested-With").build();
			}
			System.out.println("Openpay records deleted for card_id " + cardId.trim());
			
			// Delete card in our records
			PreparedStatement stmt = conn.prepareStatement("UPDATE card_info SET is_active = 'N' WHERE card_id = ?");
			stmt.setInt(1, Integer.parseInt(cardId.trim()));
			int rn = stmt.executeUpdate();
			endConn();
			System.out.println("aNet database records deactivated for card_id " + cardId.trim());
			return Response.status(200).entity("{\"status\":\"success\",\"status_code\":\"200\",\"updated_rows\":\"" + rn + "\"}").header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Headers", "Content-Type, Accept, X-Requested-With").build();
		} catch (SQLException sqlex) {
			endConn();
			sqlex.printStackTrace();
			return Response.status(500).entity("{\"status\":\"error\",\"status_code\":\"500\",\"error_msg\":\"There was an error in the query to the database. Please verify your query or contact a system administrator for help with the issue\"}").header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Headers", "Content-Type, Accept, X-Requested-With").build();
		}
	}
    
    /**
	   * This method queries transaction on the database (purchase_history) for a particular user.
	   * It brings all transaction for a particular user
	   * @param userId The email/user_id used to narrow the search
	   * @return Response This returns a json string with the user's card information on success,
	   * or an error message on error.
	   */
    @GET
    @Path("users/{user_id}/transactions")
    @Consumes(MediaType.TEXT_HTML)
    @Produces(MediaType.APPLICATION_JSON)
    public Response getAllTransactions(@PathParam("user_id") String userId) {
		boolean connectionError = false;
		try {
			startConn();
		} catch (SQLException ex) {
			connectionError = true;
		}
		if (connectionError) {
			return Response.status(500).entity("{\"status\":\"error\",\"status_code\":\"500\",\"error_msg\":\"Could not connect to the database. Please verify the issue with a system administrator\"}").header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Headers", "Content-Type, Accept, X-Requested-With").build();
		}
		String query = "SELECT * FROM purchase_history WHERE user_id = " + userId;
		try {
			PreparedStatement stmt = conn.prepareStatement(query);
			ResultSet rs = stmt.executeQuery();
			List<Map<String, Object>> listOfMaps = null;
			listOfMaps = getEntitiesFromResultSet(rs, true);
			Gson gson = new GsonBuilder().serializeNulls().setDateFormat("dd MMM yyyy HH:mm:ss").create();
			String res = gson.toJson(listOfMaps);
			endConn();
			return Response.status(200).entity("{\"status\":\"success\",\"status_code\":\"200\",\"data\":" + res + "}").header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Headers", "Content-Type, Accept, X-Requested-With").build();
		} catch (SQLException sqlex) {
			endConn();
			sqlex.printStackTrace();
			return Response.status(500).entity("{\"status\":\"error\",\"status_code\":\"500\",\"error_msg\":\"There was an error in the query to the database. Please verify your query or contact a system administrator for help with the issue\"}").header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Headers", "Content-Type, Accept, X-Requested-With").build();
		}
	}
    
    /**
	   * This method queries transactions on the database (purchase_history) for a particular user.
	   * It queries a single record at a time. It can filter by using the unique transaction_id
	   * @param userId The email/user_id used to narrow the search
	   * @param transactionId The transaction_id used to narrow the search further
	   * @return Response This returns a json string with the user's card information on success,
	   * or an error message on error.
	   */
    @GET
    @Path("users/{user_id}/transactions/{transaction_id}")
    @Consumes(MediaType.TEXT_HTML)
    @Produces(MediaType.APPLICATION_JSON)
    public Response getTransactions(@PathParam("user_id") String userId, @PathParam("transaction_id") String transactionId) {
		boolean connectionError = false;
		try {
			startConn();
		} catch (SQLException ex) {
			connectionError = true;
		}
		if (connectionError) {
			return Response.status(500).entity("{\"status\":\"error\",\"status_code\":\"500\",\"error_msg\":\"Could not connect to the database. Please verify the issue with a system administrator\"}").header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Headers", "Content-Type, Accept, X-Requested-With").build();
		}
		String query = "SELECT * FROM purchase_history WHERE user_id = " + userId + " AND transaction_id = " + transactionId;
		try {
			PreparedStatement stmt = conn.prepareStatement(query);
			ResultSet rs = stmt.executeQuery();
			List<Map<String, Object>> listOfMaps = null;
			listOfMaps = getEntitiesFromResultSet(rs, true);
			Gson gson = new GsonBuilder().serializeNulls().setDateFormat("dd MMM yyyy HH:mm:ss").create();
			String res = gson.toJson(listOfMaps);
			endConn();
			return Response.status(200).entity("{\"status\":\"success\",\"status_code\":\"200\",\"data\":" + res + "}").header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Headers", "Content-Type, Accept, X-Requested-With").build();
		} catch (SQLException sqlex) {
			endConn();
			sqlex.printStackTrace();
			return Response.status(500).entity("{\"status\":\"error\",\"status_code\":\"500\",\"error_msg\":\"There was an error in the query to the database. Please verify your query or contact a system administrator for help with the issue\"}").header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Headers", "Content-Type, Accept, X-Requested-With").build();
		}
	}
    
    /**
	   * This method creates a transaction in the database (purchase_history)
	   * @param userId The user_id used to know to which user to link the transaction
	   * @param cardId The card with which the transaction was paid
	   * @param assocId The affiliate with whom the transaction was made
	   * @param amount The amount of the transaction
	   * @param currency The currency for the amount of the transaction
	   * @return Response This returns a json string with the status of the transaction
	   */
    @POST
    @Path("transactions")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_JSON)
    public Response createTransaction(@FormParam("user_id") String userId, @FormParam("card_id") String cardId, @FormParam("associate_id") String assocId, @FormParam("amount") String amount, @FormParam("currency") String currency) {
    	// Checking parameters were sent correctly
    	if (userId == null || cardId == null || amount == null || currency == null || userId.trim().equals("") || cardId.trim().equals("") || amount.trim().equals("") || currency.trim().equals("")) {
    		return Response.status(400).entity("{\"status\":\"error\",\"status_code\":\"400\",\"error_msg\":\"There was an error passing in parameters. Please try again. Contact an administrator if the problem persists\"}").header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Headers", "Content-Type, Accept, X-Requested-With").build();
    	}
    	
    	// Changing currency to BigDecimal to prevent issues
    	BigDecimal bd = new BigDecimal(amount.trim());
    	
		boolean connectionError = false;
		try {
			startConn();
		} catch (SQLException ex) {
			connectionError = true;
		}
		if (connectionError) {
			return Response.status(500).entity("{\"status\":\"error\",\"status_code\":\"500\",\"error_msg\":\"Could not connect to the database. Please verify the issue with a system administrator\"}").header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Headers", "Content-Type, Accept, X-Requested-With").build();
		} 
		try {
			PreparedStatement stmt = conn.prepareStatement("INSERT INTO purchase_history (user_id,card_id,associate_id,amount,currency) VALUES (?,?,?,?,?)");
			stmt.setInt(1, Integer.parseInt(userId.trim()));
            stmt.setInt(2, Integer.parseInt(cardId.trim()));
            if (assocId == null) {
            	stmt.setNull(3, java.sql.Types.NUMERIC);
            } else {
            	stmt.setInt(3, Integer.parseInt(assocId.trim()));
            }
            stmt.setBigDecimal(4, bd);
            stmt.setString(5, currency.trim());
			int rn = stmt.executeUpdate();
			endConn();
			return Response.status(200).entity("{\"status\":\"success\",\"status_code\":\"200\",\"updated_rows\":\"" + rn + "\"}").header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Headers", "Content-Type, Accept, X-Requested-With").build();
		} catch (SQLException sqlex) {
			endConn();
			sqlex.printStackTrace();
			return Response.status(500).entity("{\"status\":\"error\",\"status_code\":\"500\",\"error_msg\":\"There was an error in the query to the database. Please verify your query or contact a system administrator for help with the issue\"}").header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Headers", "Content-Type, Accept, X-Requested-With").build();
		}
	}
    
    /**
	   * This method updates a transaction in the database (purchase_history)
	   * @param userId The user_id used to know to which user to link the transaction
	   * @param transactionId The transaction id to fetch the exact record
	   * @param cardId The card with which the transaction was paid
	   * @param assocId The affiliate with whom the transaction was made
	   * @param amount The amount of the transaction
	   * @param currency The currency for the amount of the transaction
	   * @return Response This returns a json string with the status of the transaction
	   */
    /*@PUT
    @Path("users/{user_id}/transactions/{transaction_id}")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_JSON)
    public Response updateAfilliate(@PathParam("user_id") String userId, @PathParam("transaction_id") String transactionId, @FormParam("associate_id") String assocId, @FormParam("card_id") String cardId, @FormParam("amount") String amount, @FormParam("currency") String currency) {
    	// Checking parameters were sent correctly
    	if (userId == null || cardId == null || assocId == null || amount == null || currency == null || userId.trim().equals("") || cardId.trim().equals("") || assocId.trim().equals("") || amount.trim().equals("") || currency.trim().equals("")) {
    		return Response.status(400).entity("{\"status\":\"error\",\"status_code\":\"400\",\"error_msg\":\"There was an error passing in parameters. Please try again. Contact an administrator if the problem persists\"}").header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Headers", "Content-Type, Accept, X-Requested-With").build();
    	}
    	
    	// Changing currency to BigDecimal to prevent issues
    	BigDecimal bd = new BigDecimal(amount.trim());
    	
		boolean connectionError = false;
		try {
			startConn();
		} catch (SQLException ex) {
			connectionError = true;
		}
		if (connectionError) {
			return Response.status(500).entity("{\"status\":\"error\",\"status_code\":\"500\",\"error_msg\":\"Could not connect to the database. Please verify the issue with a system administrator\"}").header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Headers", "Content-Type, Accept, X-Requested-With").build();
		} 
		try {
			PreparedStatement stmt = conn.prepareStatement("UPDATE purchase_history SET user_id = ?, card_id = ?, associate_id = ?, amount = ?, currency = ? WHERE transaction_id = ?");
			stmt.setString(1, userId.trim());
            stmt.setString(2, cardId.trim());
            stmt.setString(3, assocId.trim());
            stmt.setBigDecimal(4, bd);
            stmt.setString(5, currency.trim());
            stmt.setString(6, transactionId.trim());
			int rn = stmt.executeUpdate();
			endConn();
			return Response.status(200).entity("{\"status\":\"success\",\"status_code\":\"200\",\"updated_rows\":\"" + rn + "\"}").header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Headers", "Content-Type, Accept, X-Requested-With").build();
		} catch (SQLException sqlex) {
			endConn();
			sqlex.printStackTrace();
			return Response.status(500).entity("{\"status\":\"error\",\"status_code\":\"500\",\"error_msg\":\"There was an error in the query to the database. Please verify your query or contact a system administrator for help with the issue\"}").header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Headers", "Content-Type, Accept, X-Requested-With").build();
		}
	} */
    
    /**
	   * This method deletes a transaction record from the database (purchase_history).
	   * @param userId The user_id used to narrow the search
	   * @return Response This returns a json string with the status of the transaction
	   */
    /*@DELETE
    @Path("users/{user_id}/transactions/{transaction_id}")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_JSON)
    public Response deleteTransactions(@PathParam("user_id") String userId, @PathParam("transaction_id") String transactionId) {
		boolean connectionError = false;
		try {
			startConn();
		} catch (SQLException ex) {
			connectionError = true;
		}
		if (connectionError) {
			return Response.status(500).entity("{\"status\":\"error\",\"status_code\":\"500\",\"error_msg\":\"Could not connect to the database. Please verify the issue with a system administrator\"}").header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Headers", "Content-Type, Accept, X-Requested-With").build();
		} 
		try {
			PreparedStatement stmt = conn.prepareStatement("DELETE FROM purchase_history WHERE transaction_id = ?");
			stmt.setInt(1, Integer.parseInt(transactionId.trim()));
			int rn = stmt.executeUpdate();
			endConn();
			return Response.status(200).entity("{\"status\":\"success\",\"status_code\":\"200\",\"updated_rows\":\"" + rn + "\"}").header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Headers", "Content-Type, Accept, X-Requested-With").build();
		} catch (SQLException sqlex) {
			endConn();
			sqlex.printStackTrace();
			return Response.status(500).entity("{\"status\":\"error\",\"status_code\":\"500\",\"error_msg\":\"There was an error in the query to the database. Please verify your query or contact a system administrator for help with the issue\"}").header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Headers", "Content-Type, Accept, X-Requested-With").build();
		}
	} */
    
    /**
	   * This method queries affiliate information on the database (associates).
	   * It queries every single affiliate record
	   * @return Response This returns a json string with the user's card information on success,
	   * or an error message on error.
	   */
    @GET
    @Path("affiliates")
    @Consumes(MediaType.TEXT_HTML)
    @Produces(MediaType.APPLICATION_JSON)
    public Response getAllAffiliates() {
		boolean connectionError = false;
		try {
			startConn();
		} catch (SQLException ex) {
			connectionError = true;
		}
		if (connectionError) {
			return Response.status(500).entity("{\"status\":\"error\",\"status_code\":\"500\",\"error_msg\":\"Could not connect to the database. Please verify the issue with a system administrator\"}").header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Headers", "Content-Type, Accept, X-Requested-With").build();
		}
		String query = "SELECT * FROM associates";
		try {
			PreparedStatement stmt = conn.prepareStatement(query);
			ResultSet rs = stmt.executeQuery();
			List<Map<String, Object>> listOfMaps = null;
			listOfMaps = getEntitiesFromResultSet(rs, false);
			Gson gson = new GsonBuilder().serializeNulls().setDateFormat("dd MMM yyyy HH:mm:ss").create();
			String res = gson.toJson(listOfMaps);
			endConn();
			return Response.status(200).entity("{\"status\":\"success\",\"status_code\":\"200\",\"data\":" + res + "}").header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Headers", "Content-Type, Accept, X-Requested-With").build();
		} catch (SQLException sqlex) {
			endConn();
			sqlex.printStackTrace();
			return Response.status(500).entity("{\"status\":\"error\",\"status_code\":\"500\",\"error_msg\":\"There was an error in the query to the database. Please verify your query or contact a system administrator for help with the issue\"}").header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Headers", "Content-Type, Accept, X-Requested-With").build();
		}
	}
	
    /**
	   * This method queries affiliate information on the database (associates).
	   * It queries a single record at a time. It can filter by using the unique associate_id
	   * @param assocId The associate_id used to narrow the search
	   * @return Response This returns a json string with the user's card information on success,
	   * or an error message on error.
	   */
    @GET
    @Path("affiliates/{associate_id}")
    @Consumes(MediaType.TEXT_HTML)
    @Produces(MediaType.APPLICATION_JSON)
    public Response getAffiliates(@PathParam("associate_id") String assocId) {
		boolean connectionError = false;
		try {
			startConn();
		} catch (SQLException ex) {
			connectionError = true;
		}
		if (connectionError) {
			return Response.status(500).entity("{\"status\":\"error\",\"status_code\":\"500\",\"error_msg\":\"Could not connect to the database. Please verify the issue with a system administrator\"}").header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Headers", "Content-Type, Accept, X-Requested-With").build();
		}
		String query = "SELECT * FROM associates" + " WHERE associate_id = " + assocId;
		try {
			PreparedStatement stmt = conn.prepareStatement(query);
			ResultSet rs = stmt.executeQuery();
			List<Map<String, Object>> listOfMaps = null;
			listOfMaps = getEntitiesFromResultSet(rs, false);
			Gson gson = new GsonBuilder().serializeNulls().setDateFormat("dd MMM yyyy HH:mm:ss").create();
			String res = gson.toJson(listOfMaps);
			endConn();
			return Response.status(200).entity("{\"status\":\"success\",\"status_code\":\"200\",\"data\":" + res + "}").header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Headers", "Content-Type, Accept, X-Requested-With").build();
		} catch (SQLException sqlex) {
			endConn();
			sqlex.printStackTrace();
			return Response.status(500).entity("{\"status\":\"error\",\"status_code\":\"500\",\"error_msg\":\"There was an error in the query to the database. Please verify your query or contact a system administrator for help with the issue\"}").header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Headers", "Content-Type, Accept, X-Requested-With").build();
		}
	}
    
    /**
	   * This method creates an affiliate record in the database (associates)
	   * @param name The name of the affiliate
	   * @param type The type of affiliate
	   * @param url The url that links to the affiliate's website
	   * @return Response This returns a json string with the status of the transaction
	   */
    /*@POST
    @Path("affiliates")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_JSON)
    public Response createAffiliate(@FormParam("name") String name, @FormParam("associate_type") String type, @FormParam("url") String url) {
    	// Checking parameters were sent correctly
    	if (name == null || type == null || name.trim().equals("") || type.trim().equals("")) {
    		return Response.status(400).entity("{\"status\":\"error\",\"status_code\":\"400\",\"error_msg\":\"There was an error passing in parameters. Please try again. Contact an administrator if the problem persists\"}").header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Headers", "Content-Type, Accept, X-Requested-With").build();
    	}
    	
		boolean connectionError = false;
		try {
			startConn();
		} catch (SQLException ex) {
			connectionError = true;
		}
		if (connectionError) {
			return Response.status(500).entity("{\"status\":\"error\",\"status_code\":\"500\",\"error_msg\":\"Could not connect to the database. Please verify the issue with a system administrator\"}").header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Headers", "Content-Type, Accept, X-Requested-With").build();
		} 
		try {
			PreparedStatement stmt = conn.prepareStatement("INSERT INTO associates (name,associate_type,url) VALUES (?,?,?)");
			stmt.setString(1, name.trim());
            stmt.setString(2, type.trim());
            if (url == null || url.trim().equals("")) {
            	stmt.setNull(3, java.sql.Types.VARCHAR);
            } else {
            	stmt.setString(3, url.trim());
            }
			int rn = stmt.executeUpdate();
			endConn();
			return Response.status(200).entity("{\"status\":\"success\",\"status_code\":\"200\",\"updated_rows\":\" + rn + "\"}").header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Headers", "Content-Type, Accept, X-Requested-With").build();
		} catch (SQLException sqlex) {
			endConn();
			sqlex.printStackTrace();
			return Response.status(500).entity("{\"status\":\"error\",\"status_code\":\"500\",\"error_msg\":\"There was an error in the query to the database. Please verify your query or contact a system administrator for help with the issue\"}").header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Headers", "Content-Type, Accept, X-Requested-With").build();
		}
	} */
    
    /**
	   * This method updates an affiliate record in the database (associates)
	   * @param assocId The associate_id used to narrow down the search to a specific record
	   * @param name The name of the affiliate. It is updatable
	   * @param type The type of affiliate. It is updatable
	   * @param url The url that links to the affiliate's website. It is updatable
	   * @param isActive Flag that indicates whether the affiliate is active. It is updatable
	   * @return Response This returns a json string with the status of the transaction
	   */
    /*@PUT
    @Path("affiliates/{associate_id}")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_JSON)
    public Response updateAfilliate(@PathParam("associate_id") String assocId, @FormParam("name") String name, @FormParam("associate_type") String type, @FormParam("url") String url, @FormParam("is_active") String isActive) {
    	// Checking parameters were sent correctly
    	if (name == null || type == null || isActive == null name.trim().equals("") || type.trim().equals("") || isActive.trim().equals("")) {
    		return Response.status(400).entity("{\"status\":\"error\",\"status_code\":\"400\",\"error_msg\":\"There was an error passing in parameters. Please try again. Contact an administrator if the problem persists\"}").header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Headers", "Content-Type, Accept, X-Requested-With").build();
    	}
    	
		boolean connectionError = false;
		try {
			startConn();
		} catch (SQLException ex) {
			connectionError = true;
		}
		if (connectionError) {
			return Response.status(500).entity("{\"status\":\"error\",\"status_code\":\"500\",\"error_msg\":\"Could not connect to the database. Please verify the issue with a system administrator\"}").header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Headers", "Content-Type, Accept, X-Requested-With").build();
		} 
		try {
			PreparedStatement stmt = conn.prepareStatement("UPDATE associates SET name = ?, associate_type = ?, url = ?, is_active = ?, last_update_date = CURRENT_TIMESTAMP WHERE associate_id = ?");
			stmt.setString(1, name.trim());
			stmt.setString(2, type.trim());
			if (url == null || url.trim().equals("")) {
            	stmt.setNull(3, java.sql.Types.VARCHAR);
            } else {
            	stmt.setString(3, url.trim());
            }
			stmt.setString(4, isActive.trim());
			stmt.setString(5, assocId.trim());
			int rn = stmt.executeUpdate();
			endConn();
			return Response.status(200).entity("{\"status\":\"success\",\"status_code\":\"200\",\"updated_rows\":\"" + rn + "\"}").header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Headers", "Content-Type, Accept, X-Requested-With").build();
		} catch (SQLException sqlex) {
			endConn();
			sqlex.printStackTrace();
			return Response.status(500).entity("{\"status\":\"error\",\"status_code\":\"500\",\"error_msg\":\"There was an error in the query to the database. Please verify your query or contact a system administrator for help with the issue\"}").header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Headers", "Content-Type, Accept, X-Requested-With").build();
		}
	} */
    
    /**
	   * This method deletes an affiliate record from the database (associates).
	   * @param assocId The associate_id used to narrow the search
	   * @return Response This returns a json string with the status of the transaction
	   */
    /*@DELETE
    @Path("affiliates/{associate_id}")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_JSON)
    public Response deleteAffiliate(@PathParam("associate_id") String assocId) {
		boolean connectionError = false;
		try {
			startConn();
		} catch (SQLException ex) {
			connectionError = true;
		}
		if (connectionError) {
			return Response.status(500).entity("{\"status\":\"error\",\"status_code\":\"500\",\"error_msg\":\"Could not connect to the database. Please verify the issue with a system administrator\"}").header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Headers", "Content-Type, Accept, X-Requested-With").build();
		} 
		try {
			PreparedStatement stmt = conn.prepareStatement("DELETE FROM associates WHERE associate_id = ?");
			stmt.setInt(1, Integer.parseInt(assocId.trim()));
			int rn = stmt.executeUpdate();
			endConn();
			return Response.status(200).entity("{\"status\":\"success\",\"status_code\":\"200\",\"updated_rows\":\"" + rn + "\"}").header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Headers", "Content-Type, Accept, X-Requested-With").build();
		} catch (SQLException sqlex) {
			endConn();
			sqlex.printStackTrace();
			return Response.status(500).entity("{\"status\":\"error\",\"status_code\":\"500\",\"error_msg\":\"There was an error in the query to the database. Please verify your query or contact a system administrator for help with the issue\"}").header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Headers", "Content-Type, Accept, X-Requested-With").build();
		}
	} */
    
}
