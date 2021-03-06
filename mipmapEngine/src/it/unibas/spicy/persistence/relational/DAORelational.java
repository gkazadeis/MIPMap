/*
    Copyright (C) 2007-2011  Database Group - Universita' della Basilicata
    Giansalvatore Mecca - giansalvatore.mecca@unibas.it
    Salvatore Raunich - salrau@gmail.com
    Alessandro Pappalardo - pappalardo.alessandro@gmail.com
    Gianvito Summa - gianvito.summa@gmail.com

    This file is part of ++Spicy - a Schema Mapping and Data Exchange Tool

    ++Spicy is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.

    ++Spicy is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with ++Spicy.  If not, see <http://www.gnu.org/licenses/>.
 */
 
package it.unibas.spicy.persistence.relational;

import it.unibas.spicy.model.algebra.query.operators.sql.GenerateSQL;
import it.unibas.spicy.utility.SpicyEngineConstants;
import it.unibas.spicy.model.datasource.DataSource;
import it.unibas.spicy.model.datasource.INode;
import it.unibas.spicy.model.datasource.KeyConstraint;
import it.unibas.spicy.model.datasource.nodes.AttributeNode;
import it.unibas.spicy.model.datasource.nodes.LeafNode;
import it.unibas.spicy.model.datasource.nodes.SetNode;
import it.unibas.spicy.model.datasource.nodes.TupleNode;
import it.unibas.spicy.model.mapping.proxies.ConstantDataSourceProxy;
import it.unibas.spicy.model.mapping.IDataSourceProxy;
import it.unibas.spicy.model.datasource.values.IOIDGeneratorStrategy;
import it.unibas.spicy.model.datasource.values.IntegerOIDGenerator;
import it.unibas.spicy.model.datasource.values.OID;
import it.unibas.spicy.model.paths.PathExpression;
import it.unibas.spicy.persistence.AccessConfiguration;
import it.unibas.spicy.persistence.DAOException;
import it.unibas.spicy.persistence.Types;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

public class DAORelational {

    private static Log logger = LogFactory.getLog(DAORelational.class);
    private static IOIDGeneratorStrategy oidGenerator = new IntegerOIDGenerator();
    private DBFragmentDescription dataDescription = null;
    private static int NUMBER_OF_SAMPLE = 100;
    private static int BATCH_SIZE = 500;
    private static final String TUPLE_SUFFIX = "Tuple";

    private static OID getOID() {
        return oidGenerator.getNextOID();
    }

    //////////////////////////////////////////////////////////
    //////////////////////// SCHEMA
    //////////////////////////////////////////////////////////
    public IDataSourceProxy loadSchema(int scenarioNo, AccessConfiguration accessConfiguration, DBFragmentDescription dataDescription, 
            IConnectionFactory dataSourceDB, boolean source) throws DAOException {
        this.dataDescription = dataDescription;
        IConnectionFactory connectionFactory = null;
        Connection connectionPostgres = null;
        INode root = null;
        String catalog = null;
        String schemaName = accessConfiguration.getSchemaName();
        DatabaseMetaData databaseMetaData = null;
        Connection connection = dataSourceDB.getConnection(accessConfiguration);
        IDataSourceProxy dataSource = null;
        
        AccessConfiguration accessConfigurationPostgres = new AccessConfiguration();
        accessConfigurationPostgres.setDriver(SpicyEngineConstants.ACCESS_CONFIGURATION_DRIVER);
        accessConfigurationPostgres.setUri(SpicyEngineConstants.ACCESS_CONFIGURATION_URI+SpicyEngineConstants.MAPPING_TASK_DB_NAME+scenarioNo);
        accessConfigurationPostgres.setLogin(SpicyEngineConstants.ACCESS_CONFIGURATION_LOGIN);
        accessConfigurationPostgres.setPassword(SpicyEngineConstants.ACCESS_CONFIGURATION_PASS);
        
        connectionFactory = new SimpleDbConnectionFactory();
        connectionPostgres = connectionFactory.getConnection(accessConfigurationPostgres);     
        try {            
            Statement statement = connectionPostgres.createStatement();
            
            databaseMetaData = connection.getMetaData();
            catalog = connection.getCatalog();
            if (catalog == null) {
                catalog = accessConfiguration.getUri();
                if (logger.isDebugEnabled()) logger.debug("Catalog is null. Catalog name will be: " + catalog);
            }
            root = this.createRootNode(catalog);
            
            //giannisk postgres create schemas
            if(source){                        
                String createSchemasQuery = "create schema if not exists " + GenerateSQL.SOURCE_SCHEMA_NAME + ";\n";
                createSchemasQuery += "create schema if not exists " + GenerateSQL.WORK_SCHEMA_NAME + ";\n";                        
                createSchemasQuery += "create schema if not exists " + GenerateSQL.TARGET_SCHEMA_NAME + ";";
                statement.executeUpdate(createSchemasQuery);
            }           
            
            String[] tableTypes = new String[]{"TABLE"};
            ResultSet tableResultSet = databaseMetaData.getTables(catalog, schemaName, null, tableTypes);
            while (tableResultSet.next()) {
                String tableName = tableResultSet.getString("TABLE_NAME");
                if (!this.dataDescription.checkLoadTable(tableName)) {
                    continue;
                }
                INode setTable = new SetNode(tableName);
                setTable.addChild(getTuple(databaseMetaData, catalog, schemaName, tableName, source, statement));
                setTable.setRequired(false);
                setTable.setNotNull(true);
                root.addChild(setTable);
                addNode(tableName, setTable);                
            }
            dataSource = new ConstantDataSourceProxy(new DataSource(SpicyEngineConstants.TYPE_RELATIONAL, root));
            dataSource.addAnnotation(SpicyEngineConstants.ACCESS_CONFIGURATION, accessConfiguration);
            loadPrimaryKeys(dataSource, databaseMetaData, catalog, schemaName, source, statement);
            loadForeignKeys(dataSource, databaseMetaData, catalog, schemaName, source, statement);
        } catch (Throwable ex) {
            logger.error(ex);
            throw new DAOException(ex.getMessage());
        } finally {
            if (connection != null)
              dataSourceDB.close(connection);
            if (connectionPostgres != null)
              connectionFactory.close(connectionPostgres);
        }
        return dataSource;
    }

    private INode createRootNode(String catalog) {
        INode root = new TupleNode(catalog);
        root.setNotNull(true);
        root.setRequired(true);
        root.setRoot(true);
        addNode(catalog, root);
        return root;
    }

    private TupleNode getTuple(DatabaseMetaData databaseMetaData, String catalog, String schemaName, String tableName, boolean source, Statement statement) throws SQLException {
        if (logger.isDebugEnabled()) logger.debug("\nTable: " + tableName);
        TupleNode tupleNode = new TupleNode(tableName + TUPLE_SUFFIX);
        tupleNode.setRequired(false);
        tupleNode.setNotNull(true);
        tupleNode.setVirtual(true);
        addNode(tableName + TUPLE_SUFFIX, tupleNode);
        ResultSet columnsResultSet = databaseMetaData.getColumns(catalog, schemaName, tableName, null);
        String columns="";
        while (columnsResultSet.next()) {
            String columnName = columnsResultSet.getString("COLUMN_NAME");
            columns += columnName;
            String keyColumn = tableName + "." + columnName;
            String columnType = columnsResultSet.getString("TYPE_NAME");
            /////String typeOfColumn = Types.POSTGRES_STRING;
            columns += " " + columnType + ",";            
            String isNullable = columnsResultSet.getString("IS_NULLABLE");
            if (!this.dataDescription.checkLoadAttribute(tableName, columnName)) {
                continue;
            }
            boolean isNull = false;
            if (isNullable.equalsIgnoreCase("YES")) {
                isNull = true;
            }
            else{
                //take out the last ',' character
                columns = columns.substring(0, columns.length()-1);
                columns += " NOT NULL,";             
            }
            INode columnNode = new AttributeNode(columnName);
            addNode(keyColumn, columnNode);
            columnNode.setNotNull(!isNull);
            String typeOfColumn = convertDBTypeToDataSourceType(columnType);
            columnNode.addChild(new LeafNode(typeOfColumn));
            tupleNode.addChild(columnNode);
            if (logger.isDebugEnabled()) logger.debug("\n\tColumn Name: " + columnName + "(" + columnType + ") " + " type of column= " + typeOfColumn + "[IS_Nullable: " + isNullable + "]");
        }
        //take out the last ',' character
        columns = columns.substring(0, columns.length()-1);
        //giannisk postgres create table
        String table;
        if (source){
            table = GenerateSQL.SOURCE_SCHEMA_NAME+".\""+tableName+"\"";
        }
        else{
            table = GenerateSQL.TARGET_SCHEMA_NAME+".\""+tableName+"\"";    
        }
        statement.executeUpdate("drop table if exists "+ table);
        statement.executeUpdate("create table "+ table +" ("+ columns+ ")");
        
        return tupleNode;
    }

    private void loadPrimaryKeys(IDataSourceProxy dataSource, DatabaseMetaData databaseMetaData, String catalog, String schemaName, 
            boolean source, Statement statement) throws DAOException {
        try {
            String[] tableTypes = new String[]{"TABLE"};
            ResultSet tableResultSet = databaseMetaData.getTables(catalog, schemaName, null, tableTypes);
            while (tableResultSet.next()) {
                String tableName = tableResultSet.getString("TABLE_NAME");
                if (!this.dataDescription.checkLoadTable(tableName)) {
                    logger.debug("Excluding table: " + tableName);
                    continue;
                }
                if (logger.isDebugEnabled()) logger.debug("Searching primary keys. ANALYZING TABLE  = " + tableName);
                ResultSet resultSet = databaseMetaData.getPrimaryKeys(catalog, null, tableName);
                List<PathExpression> listOfPath = new ArrayList<PathExpression>();
                while (resultSet.next()) {
                    String columnName = resultSet.getString("COLUMN_NAME");
                    if (logger.isDebugEnabled()) logger.debug("Analyzing primary key: " + columnName);
                    if (!this.dataDescription.checkLoadAttribute(tableName, columnName)) {
                        continue;
                    }
                    if (logger.isDebugEnabled()) logger.debug("Found a Primary Key: " + columnName);
                    String keyPrimary = tableName + "." + columnName;
                    listOfPath.add(DAORelationalUtility.generatePath(keyPrimary));
                    
                    //giannisk alter table, add primary key
                    ////un-comment the following if Primary Key Constraints are to be considered
                    String table;
                    if (source){
                        table = GenerateSQL.SOURCE_SCHEMA_NAME+".\""+tableName+"\"";
                    }
                    else{
                        table = GenerateSQL.TARGET_SCHEMA_NAME+".\""+tableName+"\"";    
                    }
                    
                    statement.execute(createTriggerFunction(table, tableName));
                    statement.execute(createTriggerBeforeInsert(table, tableName));
                    statement.executeUpdate("ALTER TABLE "+table+" ADD PRIMARY KEY ("+columnName+");");
                    ////
                }
                if (!listOfPath.isEmpty()) {
                    KeyConstraint keyConstraint = new KeyConstraint(listOfPath, true);
                    dataSource.addKeyConstraint(keyConstraint);
                }                
            }
        } catch (SQLException ex) {
            throw new DAOException(ex.getMessage());            
        }
    }
    
    private String createTriggerFunction(String table, String tableName){
        String tempTable = GenerateSQL.WORK_SCHEMA_NAME+".\""+tableName+"\"";
        StringBuilder result = new StringBuilder();
        result.append("CREATE OR REPLACE FUNCTION ").append(tableName).append("_check_not_equal()\n");
        result.append("RETURNS trigger AS ' begin\n");
        result.append("if exists (select * from ").append(table).append(" e where e.id = new.id) then\n");
        result.append("EXECUTE ''create table if not exists ").append(tempTable).append(" (like ").append(table).append(")''; \n");
        result.append("insert into ").append(tempTable).append(" SELECT (NEW).*; \n");
        result.append("raise NOTICE ''").append(SpicyEngineConstants.PRIMARY_KEY_CONSTR_NOTICE).append(tableName).append("'';");
        result.append("return null; \n");
        result.append("end if; \n");
        result.append("return new; \n");
        result.append("end; ' LANGUAGE plpgsql;");
        return result.toString();
    }
    
    private String createTriggerBeforeInsert(String table, String tableName){
      StringBuilder result = new StringBuilder();
      result.append("CREATE TRIGGER check_ids BEFORE INSERT ON ").append(table).append(" FOR EACH ROW EXECUTE PROCEDURE ").append(tableName).append("_check_not_equal();");
      return result.toString();
    } 

    private void loadForeignKeys(IDataSourceProxy dataSource, DatabaseMetaData databaseMetaData, String catalog, String schemaName,
            boolean source, Statement statement) {
        try {
            String[] tableTypes = new String[]{"TABLE"};
            ResultSet tableResultSet = databaseMetaData.getTables(catalog, schemaName, null, tableTypes);
            while (tableResultSet.next()) {
                String tableName = tableResultSet.getString("TABLE_NAME");
                if (!this.dataDescription.checkLoadTable(tableName)) {
                    continue;
                }
                if (logger.isDebugEnabled()) logger.debug("Searching foreign keys. ANALYZING TABLE  = " + tableName);
                ResultSet resultSet = databaseMetaData.getImportedKeys(catalog, null, tableName);
                List<String> listOfPrimaryKey = new ArrayList<String>();
                List<String> listOfForeignKey = new ArrayList<String>();
                String previousTableName = "";
                while (resultSet.next()) {
                    String pkTableName = resultSet.getString("PKTABLE_NAME");
                    String pkColumnName = resultSet.getString("PKCOLUMN_NAME");
                    String keyPrimaryKey = pkTableName + "." + pkColumnName;
                    //AttributeNode primaryKey = (AttributeNode)DataSourceFactory.getNode(keyPrimary);
                    String fkTableName = resultSet.getString("FKTABLE_NAME");
                    String fkColumnName = resultSet.getString("FKCOLUMN_NAME");
                    String keyForeignKey = fkTableName + "." + fkColumnName;

                    if (logger.isDebugEnabled()) logger.debug("Analyzing foreign key: " + keyForeignKey + " references " + keyPrimaryKey);
                    if (!this.dataDescription.checkLoadTable(pkTableName) || !this.dataDescription.checkLoadTable(fkTableName)) {
                        if (logger.isDebugEnabled()) logger.debug("Check load tables. Foreign key discarded: " + keyForeignKey + " references " + keyPrimaryKey);
                        continue;
                    }
                    if (!this.dataDescription.checkLoadAttribute(pkTableName, pkColumnName) || !this.dataDescription.checkLoadAttribute(fkTableName, fkColumnName)) {
                        if (logger.isDebugEnabled()) logger.debug("Check load attributes. Foreign key discarded: " + keyForeignKey + " references " + keyPrimaryKey);
                        continue;
                    }
                    if (logger.isDebugEnabled()) logger.debug("Analyzing Primary Key: " + keyPrimaryKey + " Found a Foreign Key: " + fkColumnName + " in table " + fkTableName);
                    /*
                    //giannisk alter table, add foreign key
                    String fkTable, pkTable;
                    if (source){
                        fkTable = GenerateSQL.SOURCE_SCHEMA_NAME+".\""+fkTableName+"\"";
                        pkTable = GenerateSQL.SOURCE_SCHEMA_NAME+".\""+pkTableName+"\"";
                    }
                    else{
                        fkTable = GenerateSQL.TARGET_SCHEMA_NAME+".\""+fkTableName+"\""; 
                        pkTable = GenerateSQL.TARGET_SCHEMA_NAME+".\""+pkTableName+"\"";
                    }
                    statement.executeUpdate("ALTER TABLE "+fkTable+" ADD FOREIGN KEY ("+fkColumnName+") REFERENCES "+pkTable+" ("+pkColumnName+");");
                    */
                    if (!listOfPrimaryKey.contains(keyPrimaryKey) && (previousTableName.equals("") || previousTableName.equals(pkTableName))) {
                        if (logger.isDebugEnabled()) logger.debug("Adding nodes to collection: " + keyPrimaryKey + " - " + keyForeignKey);
                        listOfPrimaryKey.add(keyPrimaryKey);
                        listOfForeignKey.add(keyForeignKey);
                    } else if (!listOfPrimaryKey.isEmpty() && !listOfForeignKey.isEmpty()) {
                        if (logger.isDebugEnabled()) logger.debug("Generating constraint: " + listOfForeignKey + " reference " + listOfPrimaryKey);
                        DAORelationalUtility.generateConstraint(listOfForeignKey.toArray(), listOfPrimaryKey.toArray(), dataSource);
                        listOfPrimaryKey.clear();
                        listOfForeignKey.clear();
                        listOfPrimaryKey.add(keyPrimaryKey);
                        listOfForeignKey.add(keyForeignKey);
                    }
                    previousTableName = pkTableName;
                }
                if (logger.isDebugEnabled()) logger.debug("Main loop: " + listOfForeignKey + " reference " + listOfPrimaryKey);
                if (!listOfPrimaryKey.isEmpty() && !listOfForeignKey.isEmpty()) {
                    DAORelationalUtility.generateConstraint(listOfForeignKey.toArray(), listOfPrimaryKey.toArray(), dataSource);
                }
                if (logger.isDebugEnabled()) logger.debug("Foreign keys loaded. Exiting");
            }
        } catch (SQLException ex) {
            logger.error(ex);
        }
    }

    private void addNode(String key, INode node) {
        DAORelationalUtility.addNode(key, node);
    }

    private INode getNode(String label) {
        return DAORelationalUtility.getNode(label);
    }

    /////////////////////////////////////////////////////////////
    //////////////////////// INSTANCE
    /////////////////////////////////////////////////////////////
    public void loadInstance(int scenarioNo, AccessConfiguration accessConfiguration, IDataSourceProxy dataSource, DBFragmentDescription dataDescription, IConnectionFactory dataSourceDB, 
            boolean source) throws DAOException,SQLException {
        Connection connection = dataSourceDB.getConnection(accessConfiguration);
        DatabaseMetaData databaseMetaData = null;
        String catalog = null;
        String schemaName = accessConfiguration.getSchemaName();
        IConnectionFactory connectionFactory = null;
        Connection connectionPostgres = null;
        
        AccessConfiguration accessConfigurationPostgres = new AccessConfiguration();
        accessConfigurationPostgres.setDriver(SpicyEngineConstants.ACCESS_CONFIGURATION_DRIVER);
        accessConfigurationPostgres.setUri(SpicyEngineConstants.ACCESS_CONFIGURATION_URI+SpicyEngineConstants.MAPPING_TASK_DB_NAME+scenarioNo);
        accessConfigurationPostgres.setLogin(SpicyEngineConstants.ACCESS_CONFIGURATION_LOGIN);
        accessConfigurationPostgres.setPassword(SpicyEngineConstants.ACCESS_CONFIGURATION_PASS);      
        connectionFactory = new SimpleDbConnectionFactory();
        connectionPostgres = connectionFactory.getConnection(accessConfigurationPostgres); 
        try{
            databaseMetaData = connection.getMetaData();
            catalog = connection.getCatalog();            
            String[] tableTypes = new String[]{"TABLE"};
            ResultSet tableResultSet = databaseMetaData.getTables(catalog, schemaName, null, tableTypes);           
            Statement statement = connection.createStatement();
            Statement statementPostgres = connectionPostgres.createStatement();
            while (tableResultSet.next()) {
                String tableName = tableResultSet.getString("TABLE_NAME");
                if (!this.dataDescription.checkLoadTable(tableName)) {
                    continue;
                }
                String tablePath = tableName;
                if (!schemaName.equals("")) {
                    tablePath = schemaName + ".\"" + tableName+"\"";
                }
                String newTablePath = tableName;
                if (source){
                   newTablePath =  GenerateSQL.SOURCE_SCHEMA_NAME+".\""+tableName+"\"";
                }
                else{
                   newTablePath =  GenerateSQL.TARGET_SCHEMA_NAME+".\""+tableName+"\"";
                }
                ResultSet countResult = statement.executeQuery("SELECT COUNT(*) AS instancesCount FROM " +tablePath+";");
                int instancesCount = 1;
                while(countResult.next()){
                    instancesCount = countResult.getInt("instancesCount");
                }                            
                for (int i=0; i<=((instancesCount-1)/BATCH_SIZE); i++){
                    ResultSet instancesSet = statement.executeQuery("SELECT * FROM " +tablePath+" LIMIT "+BATCH_SIZE+" OFFSET "+500*i+";");
                    ResultSetMetaData rsmd = instancesSet.getMetaData();
                    int columnsNumber = rsmd.getColumnCount();
                    String sql_insert_stmnt="";
                    while (instancesSet.next()){
                        sql_insert_stmnt += "(";
                        for (int j=1; j<=columnsNumber; j++){
                            String columnValue = instancesSet.getString(j);
                            if (instancesSet.wasNull())
                                columnValue="null";
                            //escape the character ' for SQL (the "replaceAll" method call)
                            sql_insert_stmnt += "'"+columnValue.replaceAll("'", "''") +"',";  
                        }
                        //take out the last ',' character           
                        sql_insert_stmnt = sql_insert_stmnt.substring(0, sql_insert_stmnt.length()-1);
                        sql_insert_stmnt += "),";
                    }
                    if (!sql_insert_stmnt.equals("")){
                        //take out the last ',' character           
                        sql_insert_stmnt = sql_insert_stmnt.substring(0, sql_insert_stmnt.length()-1);
                        statementPostgres.executeUpdate("insert into "+newTablePath+" values "+sql_insert_stmnt+";");
                    }
                }
            }
            loadInstanceSample(accessConfiguration, dataSource, dataDescription, dataSourceDB, null);
        }
       finally {
           if (connection != null)
             dataSourceDB.close(connection);
           if (connectionPostgres != null)
             connectionFactory.close(connectionPostgres);
        }          
    }  
    
    @SuppressWarnings("unchecked")
    public void loadTranslatedInstanceSample(int scenarioNo, IDataSourceProxy dataSource, DBFragmentDescription dataDescription, 
            IConnectionFactory dataSourceDB, String rootLabel) throws DAOException {        
        
        AccessConfiguration accessConfiguration = new AccessConfiguration();
        accessConfiguration.setDriver(SpicyEngineConstants.ACCESS_CONFIGURATION_DRIVER);
        accessConfiguration.setUri(SpicyEngineConstants.ACCESS_CONFIGURATION_URI+SpicyEngineConstants.MAPPING_TASK_DB_NAME+scenarioNo);
        accessConfiguration.setLogin(SpicyEngineConstants.ACCESS_CONFIGURATION_LOGIN);
        accessConfiguration.setPassword(SpicyEngineConstants.ACCESS_CONFIGURATION_PASS);
        accessConfiguration.setSchemaName(SpicyEngineConstants.TARGET_SCHEMA_NAME);
        
        loadInstanceSample(accessConfiguration, dataSource, dataDescription, dataSourceDB, rootLabel);
    }
    
    @SuppressWarnings("unchecked")
    public void loadInstanceSample(AccessConfiguration accessConfiguration, IDataSourceProxy dataSource, DBFragmentDescription dataDescription, 
            IConnectionFactory dataSourceDB, String rootLabel) throws DAOException {
        String catalog = null;
        INode root = null;
        String schemaName = accessConfiguration.getSchemaName();
        DatabaseMetaData databaseMetaData = null;
        Connection connection = dataSourceDB.getConnection(accessConfiguration);
        try {
            databaseMetaData = connection.getMetaData();
            catalog = connection.getCatalog();
            if (catalog == null) {
                catalog = accessConfiguration.getUri();
                if (logger.isDebugEnabled()) logger.debug("Catalog is null. Catalog name will be: " + catalog);
            }
            if (logger.isDebugEnabled()) logger.debug("Catalog: " + catalog);
            if (rootLabel == null){
                root = new TupleNode(DAORelationalUtility.getNode(catalog).getLabel(), getOID());
            }
            else{
               this.dataDescription = dataDescription;
               root = new TupleNode(rootLabel, getOID()); 
            }
            root.setRoot(true);
            String[] tableTypes = new String[]{"TABLE"};
            ResultSet tableResultSet = databaseMetaData.getTables(catalog, schemaName, null, tableTypes);
            while (tableResultSet.next()) {
                String tableName = tableResultSet.getString("TABLE_NAME");
                if (!this.dataDescription.checkLoadTable(tableName)) {
                    continue;
                }
                INode setTable = new SetNode(DAORelationalUtility.getNode(tableName).getLabel(), getOID());
                ////INode setTable = new SetNode(tableName, getOID());
                if (logger.isDebugEnabled()) logger.debug("extracting value for table " + tableName + " ....");
                getInstanceByTable(dataSourceDB, connection, schemaName, tableName, setTable);
                root.addChild(setTable);
            }
            int childrenNo=0;
            for(INode setnode: root.getChildren()){
                childrenNo = childrenNo + setnode.getChildren().size();
            } 
            //if there are any instances
            if (childrenNo > 0){
                //load only a sample of the instances to memory to show on MIPMap interface
                dataSource.addInstanceWithCheck(root); 
            }
        } catch (Throwable ex) {
            logger.error(ex);
            throw new DAOException(ex.getMessage());
        } finally {
            if (connection != null)
              dataSourceDB.close(connection);
        }       
    }

    private void getInstanceByTable(IConnectionFactory dataSourceDB, Connection connection, String schemaName, String tableName, INode setTable) throws DAOException {
        PreparedStatement statement = null;
        ResultSet resultSet = null;
        try {
            String tablePath = tableName;
            if (!schemaName.equals("")) {
                tablePath = schemaName + ".\"" + tableName+"\"";
            }
            statement = connection.prepareStatement("select * from " + tablePath);
            statement.setMaxRows(NUMBER_OF_SAMPLE);
            resultSet = statement.executeQuery();
            if (resultSet == null) {
                throw new DAOException("ResultSet is NULL!");
            }
            int counterOfSample = 0;
            while (resultSet.next() && counterOfSample < NUMBER_OF_SAMPLE) {
                counterOfSample++;
                TupleNode tupleNode = new TupleNode(getNode(tableName + TUPLE_SUFFIX).getLabel(), getOID());
                setTable.addChild(tupleNode);
                for (INode attributeNodeSchema : getNode(tableName + TUPLE_SUFFIX).getChildren()) {
                    AttributeNode attributeNode = new AttributeNode(attributeNodeSchema.getLabel(), getOID());
                    String columnName = attributeNodeSchema.getLabel();
                    Object columnvalue = resultSet.getObject(columnName);
                    LeafNode leafNode = createLeafNode(attributeNodeSchema, columnvalue);
                    attributeNode.addChild(leafNode);
                    tupleNode.addChild(attributeNode);
                }
            }
        } catch (SQLException sqle) {
            throw new DAOException(sqle.getMessage());
        } finally {
            dataSourceDB.close(resultSet);
            dataSourceDB.close(statement);
        }
    }

    private LeafNode createLeafNode(INode attributeNode, Object untypedValue) throws DAOException {
        LeafNode leafNodeInSchema = (LeafNode) attributeNode.getChild(0);
        String type = leafNodeInSchema.getLabel();
        Object typedValue = Types.getTypedValue(type, untypedValue);
        return new LeafNode(type, typedValue);
    }

    private String convertDBTypeToDataSourceType(String columnType) {
        if (columnType.toLowerCase().startsWith("varchar") || columnType.toLowerCase().startsWith("char") ||
                columnType.toLowerCase().startsWith("text") || columnType.equalsIgnoreCase("bpchar") ||
                columnType.equalsIgnoreCase("bit") || columnType.equalsIgnoreCase("mediumtext") ||
                columnType.equalsIgnoreCase("longtext")) {
            return Types.STRING;
        }
        if (columnType.equalsIgnoreCase("serial") || columnType.equalsIgnoreCase("enum")) {
            return Types.STRING;
        }
        if (columnType.equalsIgnoreCase("date")) {
            return Types.DATE;
        }
        if (columnType.equalsIgnoreCase("datetime") || columnType.equalsIgnoreCase("timestamp")) {
            return Types.DATETIME;
        }
        if (columnType.toLowerCase().startsWith("serial") || columnType.toLowerCase().startsWith("int") || columnType.toLowerCase().startsWith("tinyint") || columnType.toLowerCase().startsWith("bigint") || columnType.toLowerCase().startsWith("smallint")) {
            return Types.INTEGER;
        }
        if (columnType.toLowerCase().startsWith("float") || columnType.toLowerCase().startsWith("real") || columnType.toLowerCase().startsWith("numeric") || columnType.toLowerCase().startsWith("double")) {
            //return Types.DOUBLE;
            return Types.FLOAT;
        }
        if (columnType.equalsIgnoreCase("bool")) {
            return Types.BOOLEAN;
        }
        return Types.STRING;
    }
}
