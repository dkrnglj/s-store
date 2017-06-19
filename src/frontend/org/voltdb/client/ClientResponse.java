/* This file is part of VoltDB.
 * Copyright (C) 2008-2009 VoltDB L.L.C.
 *
 * VoltDB is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * VoltDB is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
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

package org.voltdb.client;

import java.util.List;

import org.voltdb.ClientResponseDebug;
import org.voltdb.VoltTable;

import edu.brown.hstore.Hstoreservice.Status;
import edu.brown.pools.Poolable;

/**
 *  Interface implemented by the responses that are generated for procedure invocations
 */
public interface ClientResponse extends Poolable {
    /**
     * Status code indicating the store procedure executed successfully
     */
    public static final byte SUCCESS = 1;
    /**
     * Client Handle
     * @return
     */
    public long getClientHandle();
    
    /**
     * Returns true if this transaction executed as a single-partition txn
     * @return
     */
    public boolean isSinglePartition();
    
    /**
     * Base Partition
     */
    public int getBasePartition();
    
    /**
     * Get the transaction id for this response
     * @return
     */
    public long getTransactionId();

    /**
     * Retrieve the status code returned by the server
     * @return Status code
     */
    public Status getStatus();

    /**
     * Retrieve the status code returned by the stored procedure. This code is generated by the application and
     * not VoltDB. The default value is -128.
     * @return Status code
     */
    public byte getAppStatus();

    /**
     * Get the array of {@link org.voltdb.VoltTable} results returned by the stored procedure.
     * @return An array of results. Will never be <code>null</code>, but may be length 0.
     */
    public VoltTable[] getResults();
    
    /**
     * Get the number of bytes used by the results array
     */
    public int getResultsSize();

    /**
     * Get a <code>String</code> representation of any additional information the server may have included in
     * the response. This may be an stack trace, error message, etc.
     * @return A message or <code>null</code> if there is none.
     */
    public String getStatusString();

    /**
     * Get a <code>String</code> representation of any additional information the stored procedure may have included in
     * the response. This may be an stack trace, error message, etc. This is generated by the application
     * and not VoltDB. The default value is null.
     * @return A message or <code>null</code> if there is none.
     */
    public String getAppStatusString();

    /**
     * Get the <code>Exception</code> that caused the stored procedure to fail and roll back.
     * There is no guarantee that an <code>Exception</code> will be provided.
     * @return The <code>Exception</code> that caused the procedure to fail if it is available or <code>null</code>
     *         if none was provided in the response.
     */
    public Exception getException();

    /**
     * Get an estimate of the amount of time it took for the database
     * to process the transaction from the time it was received at the initiating node to the time
     * the initiating node got the response and queued it for transmission to the client.
     * This time is an ESTIMATE
     * @return Time in milliseconds the procedure spent in the cluster
     */
    public int getClusterRoundtrip();

    /**
     * Get the amount of time it took to run the transaction through the Client API, database, and back to the
     * callback.
     * @return Time in milliseconds the procedure took to roundtrip from the client to the server
     */
    public int getClientRoundtrip();
    
    /**
     * Get the number of times this transaction was restarted on the server side for whatever reason.
     * @return The number of times this transaction has been restarted.
     */
    public int getRestartCounter();
    
    /**
     * Returns true if this transaction was speculatively executed
     * @return
     */
    public boolean isSpeculative();
    
    /**
     * Returns true if this ClientResponse has an embedded ClientResponseDebug handle
     * with additional information about the transaction.
     * @return
     * @see HStoreConf.site.txn_client_debug
     */
    public boolean hasDebug();
    public ClientResponseDebug getDebug();
    
    // added by hawk, 2013/11/5
//    public List<String> getFollowingProcedures();
    public void setInitiateTime(long initiateTime);
    public long getInitiateTime();
    public long getBatchId();
    // ended by hawk
}
