/* This file is part of VoltDB.
 * Copyright (C) 2008-2015 VoltDB Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with VoltDB.  If not, see <http://www.gnu.org/licenses/>.
 */

/***************************************************************************
 *  Copyright (C) 2017 by S-Store Project                                  *
 *  Brown University                                                       *
 *  Massachusetts Institute of Technology                                  *
 *  Portland State University                                              *
 *                                                                         *
 *  Author:  The S-Store Team (sstore.cs.brown.edu)                        *
 *                                                                         *
 *                                                                         *
 *  Permission is hereby granted, free of charge, to any person obtaining  *
 *  a copy of this software and associated documentation files (the        *
 *  "Software"), to deal in the Software without restriction, including    *
 *  without limitation the rights to use, copy, modify, merge, publish,    *
 *  distribute, sublicense, and/or sell copies of the Software, and to     *
 *  permit persons to whom the Software is furnished to do so, subject to  *
 *  the following conditions:                                              *
 *                                                                         *
 *  The above copyright notice and this permission notice shall be         *
 *  included in all copies or substantial portions of the Software.        *
 *                                                                         *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,        *
 *  EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF     *
 *  MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. *
 *  IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR      *
 *  OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,  *
 *  ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR  *
 *  OTHER DEALINGS IN THE SOFTWARE.                                        *
 ***************************************************************************/

package org.voltdb.jdbc;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.ParameterMetaData;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.Calendar;

import org.voltdb.VoltType;

/**
 * JDBC implementation of Prepared statements
 * modified for HStore
 */
public class JDBC4PreparedStatement extends JDBC4Statement implements java.sql.PreparedStatement
{
    private final VoltSQL Query;
    private Object[] parameters;
    private final JDBC4ParameterMetaData parameterMetaData;
    JDBC4PreparedStatement(JDBC4Connection connection, String sql) throws SQLException
    {
        super(connection);
        VoltSQL query = VoltSQL.parseSQL(sql);
        this.Query = query;
        this.parameters = this.Query.getParameterArray();
        this.parameterMetaData = new JDBC4ParameterMetaData(this, this.Query.getParameterCount()); // to be replaced with actual param count (!)
    }

    protected JDBC4PreparedStatement(JDBC4Connection connection, String sql, boolean isCallableStatement) throws SQLException
    {
        super(connection);
        VoltSQL query = null;
        if (isCallableStatement)
        {
            query = VoltSQL.parseCall(sql);
            if (!query.isOfType(VoltSQL.TYPE_EXEC)) {
                throw SQLError.get(SQLError.ILLEGAL_STATEMENT, sql);
            }
        }
        else
        {
            query = VoltSQL.parseSQL(sql);
        }
        this.Query = query;
        this.parameters = this.Query.getParameterArray();
        this.parameterMetaData = new JDBC4ParameterMetaData(this, this.Query.getParameterCount()); // to be replaced with actual param count (!)
    }

    protected synchronized void checkParameterBounds(int parameterIndex) throws SQLException
    {
        checkClosed();
        if ((parameterIndex < 1) || (parameterIndex > this.Query.getParameterCount())) {
            throw SQLError.get(SQLError.PARAMETER_NOT_FOUND, parameterIndex, this.Query.getParameterCount());
        }
    }

    // Adds a set of parameters to this PreparedStatement object's batch of commands.
    @Override
    public void addBatch() throws SQLException
    {
        checkClosed();
        if (this.Query.isOfType(VoltSQL.TYPE_EXEC,VoltSQL.TYPE_SELECT)) {
            throw SQLError.get(SQLError.ILLEGAL_STATEMENT, this.Query.toSqlString());
        }
        this.addBatch(this.Query.getExecutableQuery(this.parameters));
        this.parameters = this.Query.getParameterArray();
    }

    // Clears the current parameter values immediately.
    @Override
    public void clearParameters() throws SQLException
    {
        checkClosed();
        this.parameters = this.Query.getParameterArray();
    }

    // Executes the SQL statement in this PreparedStatement object, which may be any kind of SQL statement.
    @Override
    public boolean execute() throws SQLException
    {
        checkClosed();
        boolean result = this.execute(this.Query.getExecutableQuery(this.parameters));
        this.parameters = this.Query.getParameterArray();
        return result;
    }

    // Executes the SQL query in this PreparedStatement object and returns the ResultSet object generated by the query.
    @Override
    public ResultSet executeQuery() throws SQLException
    {
        checkClosed();
        if (!this.Query.isOfType(VoltSQL.TYPE_EXEC,VoltSQL.TYPE_SELECT)) {
            throw SQLError.get(SQLError.ILLEGAL_STATEMENT, this.Query.toSqlString());
        }
        ResultSet result = this.executeQuery(this.Query.getExecutableQuery(this.parameters));
        this.parameters = this.Query.getParameterArray();
        return result;
    }

    // Executes the SQL statement in this PreparedStatement object, which must be an SQL Data Manipulation Language (DML) statement, such as INSERT, UPDATE or DELETE; or an SQL statement that returns nothing, such as a DDL statement.
    @Override
    public int executeUpdate() throws SQLException
    {
        checkClosed();
        if (!this.Query.isOfType(VoltSQL.TYPE_EXEC,VoltSQL.TYPE_UPDATE)) {
            throw SQLError.get(SQLError.ILLEGAL_STATEMENT, this.Query.toSqlString());
        }
        int result = this.executeUpdate(this.Query.getExecutableQuery(this.parameters));
        this.parameters = this.Query.getParameterArray();
        return result;
    }

    // Retrieves a ResultSetMetaData object that contains information about the columns of the ResultSet object that will be returned when this PreparedStatement object is executed.
    @Override
    public ResultSetMetaData getMetaData() throws SQLException
    {
        checkClosed();
        throw SQLError.noSupport(); // Would need to parse query and match with database metadata.  Not going to happen for v1.0 given the poor DB metadata support we have on hand.
    }

    // Retrieves the number, types and properties of this PreparedStatement object's parameters.
    @Override
    public ParameterMetaData getParameterMetaData() throws SQLException
    {
        checkClosed();
        return this.parameterMetaData;
    }

    // Sets the designated parameter to the given java.sql.Array object.
    @Override
    public void setArray(int parameterIndex, Array x) throws SQLException
    {
        checkParameterBounds(parameterIndex);
        throw SQLError.noSupport();
    }

    // Sets the designated parameter to the given input stream.
    @Override
    public void setAsciiStream(int parameterIndex, InputStream x) throws SQLException
    {
        checkParameterBounds(parameterIndex);
        throw SQLError.noSupport();
    }

    // Sets the designated parameter to the given input stream, which will have the specified number of bytes.
    @Override
    public void setAsciiStream(int parameterIndex, InputStream x, int length) throws SQLException
    {
        checkParameterBounds(parameterIndex);
        throw SQLError.noSupport();
    }

    // Sets the designated parameter to the given input stream, which will have the specified number of bytes.
    @Override
    public void setAsciiStream(int parameterIndex, InputStream x, long length) throws SQLException
    {
        checkParameterBounds(parameterIndex);
        throw SQLError.noSupport();
    }

    // Sets the designated parameter to the given java.math.BigDecimal value.
    @Override
    public void setBigDecimal(int parameterIndex, BigDecimal x) throws SQLException
    {
        checkParameterBounds(parameterIndex);
        this.parameters[parameterIndex-1] = x == null ? VoltType.NULL_DECIMAL : x;
    }

    // Sets the designated parameter to the given input stream.
    @Override
    public void setBinaryStream(int parameterIndex, InputStream x) throws SQLException
    {
        checkParameterBounds(parameterIndex);
        throw SQLError.noSupport();
    }

    // Sets the designated parameter to the given input stream, which will have the specified number of bytes.
    @Override
    public void setBinaryStream(int parameterIndex, InputStream x, int length) throws SQLException
    {
        checkParameterBounds(parameterIndex);
        throw SQLError.noSupport();
    }

    // Sets the designated parameter to the given input stream, which will have the specified number of bytes.
    @Override
    public void setBinaryStream(int parameterIndex, InputStream x, long length) throws SQLException
    {
        checkParameterBounds(parameterIndex);
        throw SQLError.noSupport();
    }

    // Sets the designated parameter to the given java.sql.Blob object.
    @Override
    public void setBlob(int parameterIndex, Blob x) throws SQLException
    {
        checkParameterBounds(parameterIndex);
        throw SQLError.noSupport();
    }

    // Sets the designated parameter to a InputStream object.
    @Override
    public void setBlob(int parameterIndex, InputStream inputStream) throws SQLException
    {
        checkParameterBounds(parameterIndex);
        throw SQLError.noSupport();
    }

    // Sets the designated parameter to a InputStream object.
    @Override
    public void setBlob(int parameterIndex, InputStream inputStream, long length) throws SQLException
    {
        checkParameterBounds(parameterIndex);
        throw SQLError.noSupport();
    }

    // Sets the designated parameter to the given Java boolean value.
    @Override
    public void setBoolean(int parameterIndex, boolean x) throws SQLException
    {
        checkParameterBounds(parameterIndex);
        throw SQLError.noSupport();
    }

    // Sets the designated parameter to the given Java byte value.
    @Override
    public void setByte(int parameterIndex, byte x) throws SQLException
    {
        checkParameterBounds(parameterIndex);
        this.parameters[parameterIndex-1] = x;
    }

    // Sets the designated parameter to the given Java array of bytes.
    @Override
    public void setBytes(int parameterIndex, byte[] x) throws SQLException
    {
        checkParameterBounds(parameterIndex);
        this.parameters[parameterIndex-1] = x;
    }

    // Sets the designated parameter to the given Reader object.
    @Override
    public void setCharacterStream(int parameterIndex, Reader reader) throws SQLException
    {
        checkParameterBounds(parameterIndex);
        throw SQLError.noSupport();
    }

    // Sets the designated parameter to the given Reader object, which is the given number of characters long.
    @Override
    public void setCharacterStream(int parameterIndex, Reader reader, int length) throws SQLException
    {
        checkParameterBounds(parameterIndex);
        throw SQLError.noSupport();
    }

    // Sets the designated parameter to the given Reader object, which is the given number of characters long.
    @Override
    public void setCharacterStream(int parameterIndex, Reader reader, long length) throws SQLException
    {
        checkParameterBounds(parameterIndex);
        throw SQLError.noSupport();
    }

    // Sets the designated parameter to the given java.sql.Clob object.
    @Override
    public void setClob(int parameterIndex, Clob x) throws SQLException
    {
        checkParameterBounds(parameterIndex);
        throw SQLError.noSupport();
    }

    // Sets the designated parameter to a Reader object.
    @Override
    public void setClob(int parameterIndex, Reader reader) throws SQLException
    {
        checkParameterBounds(parameterIndex);
        throw SQLError.noSupport();
    }

    // Sets the designated parameter to a Reader object.
    @Override
    public void setClob(int parameterIndex, Reader reader, long length) throws SQLException
    {
        checkParameterBounds(parameterIndex);
        throw SQLError.noSupport();
    }

    // Sets the designated parameter to the given java.sql.Date value using the default time zone of the virtual machine that is running the application.
    @Override
    public void setDate(int parameterIndex, Date x) throws SQLException
    {
        checkParameterBounds(parameterIndex);
        throw SQLError.noSupport();
    }

    // Sets the designated parameter to the given java.sql.Date value, using the given Calendar object.
    @Override
    public void setDate(int parameterIndex, Date x, Calendar cal) throws SQLException
    {
        checkParameterBounds(parameterIndex);
        throw SQLError.noSupport();
    }

    // Sets the designated parameter to the given Java double value.
    @Override
    public void setDouble(int parameterIndex, double x) throws SQLException
    {
        checkParameterBounds(parameterIndex);
        this.parameters[parameterIndex-1] = x;
    }

    // Sets the designated parameter to the given Java float value.
    @Override
    public void setFloat(int parameterIndex, float x) throws SQLException
    {
        checkParameterBounds(parameterIndex);
        this.parameters[parameterIndex-1] = (double) x;
    }

    // Sets the designated parameter to the given Java int value.
    @Override
    public void setInt(int parameterIndex, int x) throws SQLException
    {
        checkParameterBounds(parameterIndex);
        this.parameters[parameterIndex-1] = x;
    }

    // Sets the designated parameter to the given Java long value.
    @Override
    public void setLong(int parameterIndex, long x) throws SQLException
    {
        checkParameterBounds(parameterIndex);
        this.parameters[parameterIndex-1] = x;
    }

    // Sets the designated parameter to a Reader object.
    @Override
    public void setNCharacterStream(int parameterIndex, Reader value) throws SQLException
    {
        checkParameterBounds(parameterIndex);
        throw SQLError.noSupport();
    }

    // Sets the designated parameter to a Reader object.
    @Override
    public void setNCharacterStream(int parameterIndex, Reader value, long length) throws SQLException
    {
        checkParameterBounds(parameterIndex);
        throw SQLError.noSupport();
    }

    // Sets the designated parameter to a java.sql.NClob object.
    @Override
    public void setNClob(int parameterIndex, NClob value) throws SQLException
    {
        checkParameterBounds(parameterIndex);
        throw SQLError.noSupport();
    }

    // Sets the designated parameter to a Reader object.
    @Override
    public void setNClob(int parameterIndex, Reader reader) throws SQLException
    {
        checkParameterBounds(parameterIndex);
        throw SQLError.noSupport();
    }

    // Sets the designated parameter to a Reader object.
    @Override
    public void setNClob(int parameterIndex, Reader reader, long length) throws SQLException
    {
        checkParameterBounds(parameterIndex);
        throw SQLError.noSupport();
    }

    // Sets the designated paramter to the given String object.
    @Override
    public void setNString(int parameterIndex, String value) throws SQLException
    {
        checkParameterBounds(parameterIndex);
        throw SQLError.noSupport();
    }

    // Sets the designated parameter to SQL NULL.
    @Override
    public void setNull(int parameterIndex, int sqlType) throws SQLException
    {
        checkParameterBounds(parameterIndex);
        switch(sqlType)
        {
            case Types.TINYINT:
                this.parameters[parameterIndex-1] = VoltType.NULL_TINYINT;
                break;
            case Types.SMALLINT:
                this.parameters[parameterIndex-1] = VoltType.NULL_SMALLINT;
                break;
            case Types.INTEGER:
                this.parameters[parameterIndex-1] = VoltType.NULL_INTEGER;
                break;
            case Types.BIGINT:
                this.parameters[parameterIndex-1] = VoltType.NULL_BIGINT;
                break;
            case Types.DOUBLE:
                this.parameters[parameterIndex-1] = VoltType.NULL_FLOAT;
                break;
            case Types.DECIMAL:
                this.parameters[parameterIndex-1] = VoltType.NULL_DECIMAL;
                break;
            case Types.TIMESTAMP:
                this.parameters[parameterIndex-1] = VoltType.NULL_TIMESTAMP;
                break;
            case Types.VARBINARY:
            case Types.VARCHAR:
            case Types.NVARCHAR:
                this.parameters[parameterIndex-1] = VoltType.NULL_STRING_OR_VARBINARY;
                break;
            default:
                throw SQLError.get(SQLError.ILLEGAL_ARGUMENT);
        }
    }

    // Sets the designated parameter to SQL NULL.
    @Override
    public void setNull(int parameterIndex, int sqlType, String typeName) throws SQLException
    {
        this.setNull(parameterIndex, sqlType);
    }

    // Sets the value of the designated parameter using the given object.
    @Override
    public void setObject(int parameterIndex, Object x) throws SQLException
    {
        checkParameterBounds(parameterIndex);
        this.parameters[parameterIndex-1] = x;
    }

    // Sets the value of the designated parameter with the given object.
    @Override
    public void setObject(int parameterIndex, Object x, int targetSqlType) throws SQLException
    {
        checkParameterBounds(parameterIndex);
        switch(targetSqlType)
        {
            case Types.TINYINT:
                setByte(parameterIndex, ((Byte)x).byteValue());
                break;
            case Types.SMALLINT:
                setShort(parameterIndex, ((Short)x).shortValue());
                break;
            case Types.INTEGER:
                setInt(parameterIndex, ((Integer)x).intValue());
                break;
            case Types.BIGINT:
                setLong(parameterIndex, ((Long)x).longValue());
                break;
            case Types.DOUBLE:
                setDouble(parameterIndex, ((Double)x).doubleValue());
                break;
            case Types.DECIMAL:
                setBigDecimal(parameterIndex, (BigDecimal)x);
                break;
            case Types.TIMESTAMP:
                setTimestamp(parameterIndex, (Timestamp)x);
                break;
            case Types.VARBINARY:
            case Types.VARCHAR:
            case Types.NVARCHAR:
                setString(parameterIndex, (String)x);
                break;
            default:
                throw SQLError.get(SQLError.ILLEGAL_ARGUMENT);
        }
    }

    // Sets the value of the designated parameter with the given object.
    @Override
    public void setObject(int parameterIndex, Object x, int targetSqlType, int scaleOrLength) throws SQLException
    {
        checkParameterBounds(parameterIndex);
        throw SQLError.noSupport();
    }

    // Sets the designated parameter to the given REF(<structured-type>) value.
    @Override
    public void setRef(int parameterIndex, Ref x) throws SQLException
    {
        checkParameterBounds(parameterIndex);
        throw SQLError.noSupport();
    }

    // Sets the designated parameter to the given java.sql.RowId object.
    @Override
    public void setRowId(int parameterIndex, RowId x) throws SQLException
    {
        checkParameterBounds(parameterIndex);
        throw SQLError.noSupport();
    }

    // Sets the designated parameter to the given Java short value.
    @Override
    public void setShort(int parameterIndex, short x) throws SQLException
    {
        checkParameterBounds(parameterIndex);
        this.parameters[parameterIndex-1] = x;
    }

    // Sets the designated parameter to the given java.sql.SQLXML object.
    @Override
    public void setSQLXML(int parameterIndex, SQLXML xmlObject) throws SQLException
    {
        checkParameterBounds(parameterIndex);
        throw SQLError.noSupport();
    }

    // Sets the designated parameter to the given Java String value.
    @Override
    public void setString(int parameterIndex, String x) throws SQLException
    {
        checkParameterBounds(parameterIndex);
        this.parameters[parameterIndex-1] = x == null ? VoltType.NULL_STRING_OR_VARBINARY : x;
    }

    // Sets the designated parameter to the given java.sql.Time value.
    @Override
    public void setTime(int parameterIndex, Time x) throws SQLException
    {
        checkParameterBounds(parameterIndex);
        throw SQLError.noSupport();
    }

    // Sets the designated parameter to the given java.sql.Time value, using the given Calendar object.
    @Override
    public void setTime(int parameterIndex, Time x, Calendar cal) throws SQLException
    {
        checkParameterBounds(parameterIndex);
        throw SQLError.noSupport();
    }

    // Sets the designated parameter to the given java.sql.Timestamp value.
    @Override
    public void setTimestamp(int parameterIndex, Timestamp x) throws SQLException
    {
        checkParameterBounds(parameterIndex);
        this.parameters[parameterIndex-1] = x == null ? VoltType.NULL_TIMESTAMP : x;
    }

    // Sets the designated parameter to the given java.sql.Timestamp value, using the given Calendar object.
    @Override
    public void setTimestamp(int parameterIndex, Timestamp x, Calendar cal) throws SQLException
    {
        checkParameterBounds(parameterIndex);
        throw SQLError.noSupport();
    }

    // Deprecated.
    @Override
    @Deprecated
    public void setUnicodeStream(int parameterIndex, InputStream x, int length) throws SQLException
    {
        checkParameterBounds(parameterIndex);
        throw SQLError.noSupport();
    }

    // Sets the designated parameter to the given java.net.URL value.
    @Override
    public void setURL(int parameterIndex, URL x) throws SQLException
    {
        checkParameterBounds(parameterIndex);
        this.parameters[parameterIndex-1] = x == null ? VoltType.NULL_STRING_OR_VARBINARY : x.toString();
    }
 }
