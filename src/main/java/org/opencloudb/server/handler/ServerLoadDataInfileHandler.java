/*
 * Copyright (c) 2013, OpenCloudDB/MyCAT and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software;Designed and Developed mainly by many Chinese 
 * opensource volunteers. you can redistribute it and/or modify it under the 
 * terms of the GNU General Public License version 2 only, as published by the
 * Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 * 
 * Any questions about this component can be directed to it's project Web address 
 * https://code.google.com/p/opencloudb/.
 *
 */
package org.opencloudb.server.handler;

import com.alibaba.druid.sql.ast.SQLExpr;
import com.alibaba.druid.sql.ast.expr.SQLCharExpr;
import com.alibaba.druid.sql.ast.expr.SQLLiteralExpr;
import com.alibaba.druid.sql.ast.expr.SQLTextLiteralExpr;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlLoadDataInFileStatement;
import com.alibaba.druid.sql.parser.SQLStatementParser;
import com.google.common.collect.Lists;
import com.google.common.io.Files;
import com.univocity.parsers.csv.CsvParser;
import com.univocity.parsers.csv.CsvParserSettings;
import org.opencloudb.MycatServer;
import org.opencloudb.cache.LayerCachePool;
import org.opencloudb.config.ErrorCode;
import org.opencloudb.config.model.SchemaConfig;
import org.opencloudb.config.model.SystemConfig;
import org.opencloudb.config.model.TableConfig;
import org.opencloudb.mpp.LoadData;
import org.opencloudb.net.handler.LoadDataInfileHandler;
import org.opencloudb.net.mysql.BinaryPacket;
import org.opencloudb.net.mysql.RequestFilePacket;
import org.opencloudb.parser.druid.DruidShardingParseInfo;
import org.opencloudb.parser.druid.MycatStatementParser;
import org.opencloudb.route.RouteResultset;
import org.opencloudb.route.RouteResultsetNode;
import org.opencloudb.route.util.RouterUtil;
import org.opencloudb.server.ServerConnection;
import org.opencloudb.server.parser.ServerParse;
import org.opencloudb.util.ObjectUtil;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.sql.SQLNonTransientException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * mysql命令行客户端也需要启用local file权限，加参数--local-infile=1
 * jdbc则正常，不用设置
 * load data sql中的CHARACTER SET 'gbk'   其中的字符集必须引号括起来，否则druid解析出错
 */
public final class ServerLoadDataInfileHandler implements LoadDataInfileHandler
{
    private ServerConnection serverConnection;
    private String sql;
    private String fileName;
    private byte packID = 0;
    private MySqlLoadDataInFileStatement statement;

    private Map<String, LoadData> routeResultMap = new HashMap<>();

    private LoadData loadData;
    private ByteArrayOutputStream tempByteBuffer;
    private long tempByteBuffrSize = 0;
    private String tempFile;
    private boolean isHasStoreToFile = false;
    private String tempPath;
    private String tableName;
    private TableConfig tableConfig;
    private int partitionColumnIndex = -1;
    private LayerCachePool tableId2DataNodeCache;
    private SchemaConfig schema;

    public int getPackID()
    {
        return packID;
    }

    public void setPackID(byte packID)
    {
        this.packID = packID;
    }

    public ServerLoadDataInfileHandler(ServerConnection serverConnection)
    {
        this.serverConnection = serverConnection;

    }

    private static String parseFileName(String sql)
    {
        if (sql.contains("'"))
        {
            int beginIndex = sql.indexOf("'");
            return sql.substring(beginIndex + 1, sql.indexOf("'", beginIndex + 1));
        } else if (sql.contains("\""))
        {
            int beginIndex = sql.indexOf("\"");
            return sql.substring(beginIndex + 1, sql.indexOf("\"", beginIndex + 1));
        }
        return null;
    }


    private void parseLoadDataPram()
    {
        loadData = new LoadData();
        SQLTextLiteralExpr rawLineEnd = (SQLTextLiteralExpr) statement.getLinesTerminatedBy();
        String lineTerminatedBy = rawLineEnd == null ? "\n" : rawLineEnd.getText();
        loadData.setLineTerminatedBy(lineTerminatedBy);

        SQLTextLiteralExpr rawFieldEnd = (SQLTextLiteralExpr) statement.getColumnsTerminatedBy();
        String fieldTerminatedBy = rawFieldEnd == null ? "\t" : rawFieldEnd.getText();
        loadData.setFieldTerminatedBy(fieldTerminatedBy);

        SQLTextLiteralExpr rawEnclosed = (SQLTextLiteralExpr) statement.getColumnsEnclosedBy();
        String enclose = rawEnclosed == null ? null : rawEnclosed.getText();
        loadData.setEnclose(enclose);

        String charset = statement.getCharset() != null ? statement.getCharset() : serverConnection.getCharset();
        loadData.setCharset(charset);
        loadData.setFileName(fileName);
    }


    @Override
    public void start(String sql)
    {
        clear();
        this.sql = sql;


        SQLStatementParser parser = new MycatStatementParser(sql);
        statement = (MySqlLoadDataInFileStatement) parser.parseStatement();
        fileName = parseFileName(sql);

        if (fileName == null)
        {
            serverConnection.writeErrMessage(ErrorCode.ER_FILE_NOT_FOUND, " file name is null !");
            clear();
            return;
        }
        schema = MycatServer.getInstance().getConfig()
                .getSchemas().get(serverConnection.getSchema());
        tableId2DataNodeCache = (LayerCachePool) MycatServer.getInstance().getCacheService().getCachePool("TableID2DataNodeCache");
        tableName = statement.getTableName().getSimpleName().toUpperCase();
        tableConfig = schema.getTables().get(tableName);
        tempPath = SystemConfig.getHomePath() + File.separator + "temp" + File.separator + serverConnection.getId() + File.separator;
        tempFile = tempPath + "clientTemp.txt";
        tempByteBuffer = new ByteArrayOutputStream();

        List<SQLExpr> columns = statement.getColumns();
        String pColumn = tableConfig.getPartitionColumn();
        if (columns != null && columns.size() > 0)
        {

            for (int i = 0, columnsSize = columns.size(); i < columnsSize; i++)
            {
                SQLExpr column = columns.get(i);
                if (pColumn.equalsIgnoreCase(column.toString()))
                {
                    partitionColumnIndex = i;
                    break;

                }

            }

        }


        parseLoadDataPram();
        if (statement.isLocal())
        {
            //向客户端请求发送文件
            ByteBuffer buffer = serverConnection.allocate();
            RequestFilePacket filePacket = new RequestFilePacket();
            filePacket.fileName = fileName.getBytes();
            filePacket.packetId = 1;
            filePacket.write(buffer, serverConnection, true);
        } else
        {
            if (!new File(fileName).exists())
            {
                serverConnection.writeErrMessage(ErrorCode.ER_FILE_NOT_FOUND, fileName + " is not found!");
                clear();
            } else
            {
                parseFileByLine(fileName, loadData.getCharset(), loadData.getLineTerminatedBy());
                RouteResultset rrs = buildResultSet(routeResultMap);
                if (rrs != null)
                {
                    serverConnection.getSession2().execute(rrs, ServerParse.LOAD_DATA_INFILE_SQL);
                }
                //   sendOk((byte) 1);
                clear();
            }
        }
    }

    @Override
    public void handle(byte[] data)
    {

        try
        {
            if (sql == null)
            {
                serverConnection.writeErrMessage(ErrorCode.ER_UNKNOWN_COM_ERROR,
                        "Unknown command");
                clear();
                return;
            }
            BinaryPacket packet = new BinaryPacket();
            ByteArrayInputStream inputStream = new ByteArrayInputStream(data, 0, data.length);
            packet.read(inputStream);

            saveByteOrToFile(packet.data, false);


        } catch (IOException e)
        {
            throw new RuntimeException(e);
        }


    }

    private synchronized void saveByteOrToFile(byte[] data, boolean isForce)
    {

        if (data != null)
        {
            tempByteBuffrSize = tempByteBuffrSize + data.length;
            try
            {
                tempByteBuffer.write(data);
            } catch (IOException e)
            {
                throw new RuntimeException(e);
            }
        }

        if ((isForce && isHasStoreToFile) || tempByteBuffrSize > 200 * 1024 * 1024)    //超过200M 存文件
        {
            FileOutputStream channel = null;
            try
            {
                File file = new File(tempFile);
                Files.createParentDirs(file);
                channel = new FileOutputStream(file, true);

                tempByteBuffer.writeTo(channel);
                tempByteBuffer.reset();
               tempByteBuffrSize = 0;
                isHasStoreToFile = true;
            } catch (IOException e)
            {
                throw new RuntimeException(e);
            } finally
            {
                try
                {
                    if (channel != null)
                        channel.close();

                } catch (IOException ignored)
                {

                }
            }


        }
    }


    private RouteResultset tryDirectRoute(String sql, String[] lineList)
    {

        RouteResultset rrs = new RouteResultset(sql, ServerParse.INSERT);
        rrs.setLoadData(true);
        if (tableConfig == null && schema.getDataNode() != null)
        {
            //走默认节点
            RouteResultsetNode rrNode = new RouteResultsetNode(schema.getDataNode(), ServerParse.INSERT, sql);
            rrs.setNodes(new RouteResultsetNode[]{rrNode});
            return rrs;
        } else if (tableConfig != null)
        {
            DruidShardingParseInfo ctx = new DruidShardingParseInfo();
            ctx.addTable(tableName);


            if (partitionColumnIndex == -1 || partitionColumnIndex >= lineList.length)
            {
                return null;
            } else
            {
                String value = lineList[partitionColumnIndex];
                ctx.addShardingExpr(tableName, tableConfig.getPartitionColumn(), parseFieldString(value, loadData.getEnclose()));
                try
                {
                    RouterUtil.tryRouteForTables(schema, ctx, rrs, false, tableId2DataNodeCache);
                    return rrs;
                } catch (SQLNonTransientException e)
                {
                    throw new RuntimeException(e);
                }
            }


        }

        return null;
    }


    private void parseOneLine(List<SQLExpr> columns, String tableName, String[] line, boolean toFile, String lineEnd)
    {

        RouteResultset rrs = tryDirectRoute(sql, line);
        if (rrs == null || rrs.getNodes() == null || rrs.getNodes().length == 0)
        {

            String insertSql = makeSimpleInsert(columns, line, tableName, true);
            rrs = serverConnection.routeSQL(insertSql, ServerParse.INSERT);
        }


        if (rrs == null || rrs.getNodes() == null || rrs.getNodes().length == 0)
        {
            //无路由处理
        } else
        {
            for (RouteResultsetNode routeResultsetNode : rrs.getNodes())
            {
                String name = routeResultsetNode.getName();
                LoadData data = routeResultMap.get(name);
                if (data == null)
                {
                    data = new LoadData();
                    routeResultMap.put(name, data);
                }
                if (toFile)
                {
                    if (data.getFileName() == null)
                    {
                        String dnPath = tempPath + name + ".txt";
                        data.setFileName(dnPath);
                        try
                        {
                            File dnFile = new File(dnPath);
                            Files.createParentDirs(dnFile);
                            String jLine = join(line, loadData, null);
                            ;
                            Files.write(jLine, dnFile, Charset.forName(loadData.getCharset()));
                        } catch (IOException e)
                        {
                            throw new RuntimeException(e);
                        }
                    } else
                    {
                        File dnFile = new File(data.getFileName());
                        String jLine = join(line, loadData, lineEnd);
                        ;
                        try
                        {
                            Files.append(jLine, dnFile, Charset.forName(loadData.getCharset()));
                        } catch (IOException e)
                        {
                            throw new RuntimeException(e);
                        }
                    }

                } else
                {
                    String jLine = join(line, loadData, null);
                    if (data.getData() == null)
                    {
                        data.setData(Lists.newArrayList(jLine));
                    } else
                    {

                        data.getData().add(jLine);
                    }
                }
            }
        }
    }


    private String join(String[] src, LoadData loadData, String end)
    {
        StringBuilder sb = new StringBuilder();
        for (int i = 0, srcLength = src.length; i < srcLength; i++)
        {
            String s = src[i];
            if(loadData.getEnclose()==null)
            {
                  sb.append(s);
            }   else
            {
                sb.append(loadData.getEnclose()).append(s).append(loadData.getEnclose());
            }
            if(i!=srcLength-1)
            {
                sb.append(loadData.getFieldTerminatedBy());
            }
        }
        if (end == null)
        {
            return sb.toString();
        } else
        {
            return end + sb.toString();
        }
    }


    private RouteResultset buildResultSet(Map<String, LoadData> routeMap)
    {
        statement.setLocal(true);//强制local
        SQLLiteralExpr fn = new SQLCharExpr(fileName);    //默认druid会过滤掉路径的分隔符，所以这里重新设置下
        statement.setFileName(fn);
        String srcStatement = statement.toString();
        RouteResultset rrs = new RouteResultset(srcStatement, ServerParse.LOAD_DATA_INFILE_SQL);
        rrs.setLoadData(true);
        rrs.setStatement(srcStatement);
        rrs.setAutocommit(serverConnection.isAutocommit());
        rrs.setFinishedRoute(true);
        int size = routeMap.size();
        RouteResultsetNode[] routeResultsetNodes = new RouteResultsetNode[size];
        int index = 0;
        for (String dn : routeMap.keySet())
        {
            RouteResultsetNode rrNode = new RouteResultsetNode(dn, ServerParse.LOAD_DATA_INFILE_SQL, srcStatement);
            rrNode.setTotalNodeSize(size);
            rrNode.setStatement(srcStatement);
            LoadData newLoadData = new LoadData();
            ObjectUtil.copyProperties(loadData, newLoadData);
            newLoadData.setLocal(true);
            LoadData loadData1 = routeMap.get(dn);
            if (isHasStoreToFile)
            {
                newLoadData.setFileName(loadData1.getFileName());
            } else
            {
                newLoadData.setData(loadData1.getData());
            }
            rrNode.setLoadData(newLoadData);

            routeResultsetNodes[index] = rrNode;
            index++;
        }
        rrs.setNodes(routeResultsetNodes);
        return rrs;
    }


    private String makeSimpleInsert(List<SQLExpr> columns, String[] fields, String table, boolean isAddEncose)
    {
        StringBuilder sb = new StringBuilder();
        sb.append(LoadData.loadDataHint).append("insert into ").append(table.toUpperCase());
        if (columns != null && columns.size() > 0)
        {
            sb.append("(");
            for (int i = 0, columnsSize = columns.size(); i < columnsSize; i++)
            {
                SQLExpr column = columns.get(i);
                sb.append(column.toString());
                if (i != columnsSize - 1)
                {
                    sb.append(",");
                }
            }
            sb.append(") ");
        }

        sb.append(" values (");
        for (int i = 0, columnsSize = fields.length; i < columnsSize; i++)
        {
            String column = fields[i];
            if (isAddEncose)
            {
                sb.append("'").append(parseFieldString(column, loadData.getEnclose())).append("'");
            } else
            {
                sb.append(column);
            }
            if (i != columnsSize - 1)
            {
                sb.append(",");
            }
        }
        sb.append(")");
        return sb.toString();
    }

    private String parseFieldString(String value, String encose)
    {
        if (encose == null || "".equals(encose) || value == null)
        {
            return value;
        } else if (value.startsWith(encose) && value.endsWith(encose))
        {
            return value.substring(encose.length() - 1, value.length() - encose.length());
        }
        return value;
    }


    @Override
    public void end(byte packID)
    {
        this.packID = packID;
        //load in data空包 结束
        saveByteOrToFile(null, true);
        List<SQLExpr> columns = statement.getColumns();
        String tableName = statement.getTableName().getSimpleName();
        if (isHasStoreToFile)
        {
            parseFileByLine(tempFile, loadData.getCharset(), loadData.getLineTerminatedBy());
        } else
        {
            String content = new String(tempByteBuffer.toByteArray(), Charset.forName(loadData.getCharset()));

            // List<String> lines = Splitter.on(loadData.getLineTerminatedBy()).omitEmptyStrings().splitToList(content);
            CsvParserSettings settings = new CsvParserSettings();
            settings.getFormat().setLineSeparator(loadData.getLineTerminatedBy());
            settings.getFormat().setDelimiter(loadData.getFieldTerminatedBy().charAt(0));
            if(loadData.getEnclose()!=null)
            {
                settings.getFormat().setQuote(loadData.getEnclose().charAt(0));
            }
            settings.getFormat().setNormalizedNewline(loadData.getLineTerminatedBy().charAt(0));
            CsvParser parser = new CsvParser(settings);
            try
            {
                parser.beginParsing(new StringReader(content));
                String[] row = null;

                while ((row = parser.parseNext()) != null)
                {
                    parseOneLine(columns, tableName, row, false, null);
                }
            } finally
            {
                parser.stopParsing();
            }


        }

        RouteResultset rrs = buildResultSet(routeResultMap);
        if (rrs != null)
        {
            serverConnection.getSession2().execute(rrs, ServerParse.LOAD_DATA_INFILE_SQL);
        }


        // sendOk(++packID);


    }


    private void parseFileByLine(String file, String encode, String split)
    {
        List<SQLExpr> columns = statement.getColumns();
        CsvParserSettings settings = new CsvParserSettings();
        settings.getFormat().setLineSeparator(loadData.getLineTerminatedBy());
        settings.getFormat().setDelimiter(loadData.getFieldTerminatedBy().charAt(0));
        if(loadData.getEnclose()!=null)
        {
            settings.getFormat().setQuote(loadData.getEnclose().charAt(0));
        }
        settings.getFormat().setNormalizedNewline(loadData.getLineTerminatedBy().charAt(0));
        CsvParser parser = new CsvParser(settings);
        InputStreamReader reader = null;
        try
        {

            reader = new InputStreamReader(new FileInputStream(file), encode);
            parser.beginParsing(reader);
            String[] row = null;

            while ((row = parser.parseNext()) != null)
            {
                parseOneLine(columns, tableName, row, true, loadData.getLineTerminatedBy());
            }

        } catch (FileNotFoundException | UnsupportedEncodingException e)
        {
            throw new RuntimeException(e);
        } finally
        {
            parser.stopParsing();
            if (reader != null)
            {
                try
                {
                    reader.close();
                } catch (IOException e)
                {
                    throw new RuntimeException(e);
                }
            }

        }

    }


    public void clear()
    {
        tableId2DataNodeCache = null;
        schema = null;
        tableConfig = null;
        partitionColumnIndex = -1;
        if (tempFile != null)
        {
            File temp = new File(tempFile);
            if (temp.exists())
            {
                temp.delete();
            }
        }
        if (tempPath != null && new File(tempPath).exists())
        {
            deleteFile(tempPath);
        }
        tempByteBuffer = null;
        loadData = null;
        sql = null;
        fileName = null;
        statement = null;
        routeResultMap.clear();
    }

    @Override
    public byte getLastPackId()
    {
        return packID;
    }

    /**
     * 删除目录及其所有子目录和文件
     *
     * @param dirPath 要删除的目录路径
     * @throws Exception
     */
    private static void deleteFile(String dirPath)
    {
        File fileDirToDel = new File(dirPath);
        if (!fileDirToDel.exists())
        {
            return;
        }
        if (fileDirToDel.isFile())
        {
            fileDirToDel.delete();
            return;
        }
        File[] fileList = fileDirToDel.listFiles();

        for (int i = 0; i < fileList.length; i++)
        {
            if (fileList[i].isFile())
            {
                fileList[i].delete();
            } else if (fileList[i].isDirectory())
            {
                deleteFile(fileList[i].getAbsolutePath());
                fileList[i].delete();
            }
        }
        fileDirToDel.delete();
    }


}