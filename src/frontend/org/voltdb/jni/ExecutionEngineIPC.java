/* This file is part of VoltDB.
 * Copyright (C) 2008-2010 VoltDB Inc.
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

package org.voltdb.jni;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.voltdb.BackendTarget;
import org.voltdb.DependencySet;
import org.voltdb.ParameterSet;
import org.voltdb.PrivateVoltTableFactory;
import org.voltdb.SysProcSelector;
import org.voltdb.TableStreamType;
import org.voltdb.VoltTable;
import org.voltdb.catalog.Table;
import org.voltdb.exceptions.EEException;
import org.voltdb.exceptions.SerializableException;
import org.voltdb.export.ExportProtoMessage;
import org.voltdb.messaging.FastDeserializer;
import org.voltdb.messaging.FastSerializer;
import org.voltdb.utils.DBBPool.BBContainer;
import org.voltdb.utils.NotImplementedException;

import edu.brown.hstore.HStore;
import edu.brown.hstore.PartitionExecutor;


/* Serializes data over a connection that presumably is being read
 * by a voltdb execution engine. The serialization is currently a
 * a packed binary big endian buffer. Each buffer has a header
 * that provides the total length (as an int) and the command being
 * serialized (also as an int). Commands are described by the Commands
 * enum.
 *
 * These serializations are implemented as required since a lot of
 * the native interface isn't needed in many cases.
 *
 * The serialization could be more robust. Probably the right way to
 * get that robustness would be to create FastSerializable classes
 * for the more complex calls and read them through the fast serializable
 * C++ interface.
 *
 * (The fastserializer still requires hand-authoring the ser/deser
 * logic in java and C and doesn't result in any more favorable
 * packing; in fact the resulting wire content might be the same in
 * most cases... And you can't fast serialize an array of primitive
 * types without writing a wrapper object.)
 *
 * Responses to the IPC interface start with a 1 byte result code. If the
 * result code is failure then no message follows. If the result code
 * is success and a response is warranted it will follow and is length prefixed if necessary.
 * Unlike requests to the EE, the length prefix in responses does not include the 1 byte
 * result code nor the 4 byte length field. When we correct the 1 and 4 byte network reads and writes
 * we can this. A length will not prefix the response
 * if the length of the response can be deduced from the original request.
 *
 * The return message format for DMLPlanFragments is all big endian:
 * 1 byte result code
 * 8 byte results codes. Same number of results as numPlanFragments.
 *
 * The return message format for QueryPlanFragments is all big endian:
 * 1 byte result code
 * 4 byte result length. Does not include the length of the result code or result length field.
 * X bytes of serialized VoltTables. Same number of tables as numPlanFragments
 *
 * The return message format for PlanFragment is all big endian:
 * 1 byte result code
 * 4 byte result length. Does not include the length of the result code or result length field.
 * 4 byte result indicating number of dependencies returned
 * The dependency tables
 *
 * The return message format for ReceiveDependency consists of a single byte result code.
 *
 * The return message format for Load table consists of a single byte result code.
 */

public class ExecutionEngineIPC extends ExecutionEngine {
    /**
     * Exposes error generated by the Valgrind client to org.voltdb.regressionsuites.LocalSingeProcessServer
     * which will fail a test if this list is not empty.
     */
    public static final List<String> m_valgrindErrors = Collections.synchronizedList(new ArrayList<String>());

    /** Commands are serialized over the connection */
    private enum Commands {
        Initialize(0),
        LoadCatalog(2),
        ToggleProfiler(3),
        Tick(4),
        GetStats(5),
        QueryPlanFragments(6),
        PlanFragment(7),
        LoadTable(9),
        releaseUndoToken(10),
        undoUndoToken(11),
        CustomPlanFragment(12),
        SetLogLevels(13),
        Quiesce(16),
        ActivateTableStream(17),
        TableStreamSerializeMore(18),
        UpdateCatalog(19),
        ExportAction(20),
        RecoveryMessage(21),
        TableHashCode(22),
        Hashinate(23);
        Commands(final int id) {
            m_id = id;
        }

        int m_id;
    }

    private static AtomicInteger eeCount = new AtomicInteger(21214);

    /**
     * One connection per ExecutionEngineIPC. This connection also interfaces
     * with Valgrind to report any problems that Valgrind may find including
     * reachable heap blocks on exit. Unfortunately it is not enough to inspect
     * the Valgrind return code as it considers reachable blocks to not be an
     * error.
     **/
    private class Connection {
        private Socket m_socket = null;
        private SocketChannel m_socketChannel = null;
        private Process m_eeProcess;
        private String m_eePID = null;
        private Thread m_stdoutParser = null;
        // Has a received String from Valgrind saying that all heap blocks were freed
        // ignored when running directly againsts the IPC client
        private boolean m_allHeapBlocksFreed = false;
        Connection(final BackendTarget target) {
            /*
             * String ee_exec_path = System.getenv("EEIPC_PATH"); if
             * (ee_exec_path != null) { try { eeProcess =
             * Runtime.getRuntime().exec("valgrind " + ee_exec_path +
             * " > ~aweisberg/valgrind_out 2>&1 "); } catch (IOException e) {
             * throw new RuntimeException(e); } try { Thread.sleep(1000); }
             * catch (InterruptedException e) { // TODO Auto-generated catch
             * block e.printStackTrace(); } }
             */
            int port = 21214;
            if (target == BackendTarget.NATIVE_EE_IPC) {
                System.out
                        .println("Press enter after you have started the EE process to initiate the connection to the EE");
                try {
                    System.in.read();
                } catch (final IOException e1) {
                    // TODO Auto-generated catch block
                    e1.printStackTrace();
                }
            } else if (target == BackendTarget.NATIVE_EE_VALGRIND_IPC) {
                System.out.println("Running " + target);
                final ArrayList<String> args = new ArrayList<String>();
                final String voltdbIPCPath = System.getenv("VOLTDBIPC_PATH");
                args.add("valgrind");
                args.add("--leak-check=full");
                args.add("--show-reachable=yes");
                args.add("--num-callers=32");
                args.add("--error-exitcode=-1");
                /*
                 * VOLTDBIPC_PATH is set as part of the regression suites and ant check
                 * In that scenario junit will handle logging of Valgrind output.
                 * stdout and stderr is forwarded from the backend.
                 */
                if (voltdbIPCPath == null) {
                    args.add("--quiet");
                    args.add("--log-file=site_" + m_siteId + ".log");
            }
                args.add(voltdbIPCPath == null ? "./voltdbipc" : voltdbIPCPath);
                port = eeCount.getAndIncrement();
                args.add(Integer.toString(port));
                final ProcessBuilder pb = new ProcessBuilder(args);
                pb.redirectErrorStream(true);

                try {
                    m_eeProcess = pb.start();
                    final Process p = m_eeProcess;
                    Runtime.getRuntime().addShutdownHook(new Thread() {
                        @Override
                        public void run() {
                            p.destroy();
                        }
                    });
                } catch (final IOException e) {
                    e.printStackTrace();
                    HStore.crashDB();
            }

                /*
                 * This block attempts to read the PID and then waits for the listening message
                 * indicating that the IPC EE is ready to accept a connection on a socket
                 */
                final BufferedReader r = new BufferedReader(new InputStreamReader(m_eeProcess.getInputStream()));
                try {
                    boolean failure = false;
                    String pidString = r.readLine();
                    if (pidString == null) {
                        failure = true;
                    } else {
                        System.out.println("PID string \"" + pidString + "\"");
                        pidString = pidString.substring(2);
                        pidString = pidString.substring(0, pidString.indexOf("="));
                        m_eePID = pidString;
                    }
                    while (true) {
                        String line = null;
                        if (!failure) {
                            line = r.readLine();
                        }
                        if (line != null && !failure) {
                            System.out.println("[ipc=" + m_eePID + "]:::" + line);
                            if (line.contains("listening")) {
                                break;
                            } else {
                                continue;
                            }
                        } else {
                            try {
                                m_eeProcess.waitFor();
                            } catch (final InterruptedException e) {
                                // TODO Auto-generated catch block
                                e.printStackTrace();
                            }
                            System.out.println("[ipc=" + m_eePID + "] Returned end of stream and exit value " + m_eeProcess.exitValue());
                            HStore.crashDB();
                        }
                    }
                } catch (final IOException e) {
                    e.printStackTrace();
                    return;
                }

                /*
                 * Create a thread to parse Valgrind's output and populdate m_valgrindErrors with errors.
                 */
                final Process p = m_eeProcess;
                m_stdoutParser = new Thread() {
                    @Override
                    public void run() {
                        while (true) {
                            try {
                                final String line = r.readLine();
                                if (line != null) {
                                    System.out.println("[ipc=" + p.hashCode() + "]:::" + line);
                                    if (line.startsWith("==" + m_eePID + "==")) {
                                        processValgrindOutput(line);
                                    }
                                } else {
                                    try {
                                        p.waitFor();
                                    } catch (final InterruptedException e) {
                                        e.printStackTrace();
                                    }
                                    System.out.println("[ipc=" + m_eePID + "] Returned end of stream and exit value " + p.exitValue());
                                    if (!m_allHeapBlocksFreed) {
                                        m_valgrindErrors.add("Not all heap blocks were freed");
                                    }
                                    return;
                                }
                            } catch (final IOException e) {
                                e.printStackTrace();
                                return;
                            }
                        }
                    }

                    private void processValgrindOutput(final String line) {
                        final String errorLineString = "ERROR SUMMARY: ";
                        final String heapBlocksFreedString = "All heap blocks were freed";
                        /*
                         * An indirect way of making sure Valgrind reports no error memory accesses
                         */
                        if (line.contains(errorLineString)) {
                            final int index = line.indexOf(errorLineString) + errorLineString.length();
                            final char errorNumChar = line.charAt(index);
                            if (!(errorNumChar == '0')) {
                                m_valgrindErrors.add(line);
                            }
                        } else if (line.contains(heapBlocksFreedString)) {
                            m_allHeapBlocksFreed = true;
                        }
                    }
                };
                m_stdoutParser.setDaemon(false);
                m_stdoutParser.start();
            } else {
                throw new RuntimeException("Shouldn't instantiate an ExecutionEngineIPC with BackendTarget " + target);
            }

            try {
                m_socketChannel = SocketChannel.open(new InetSocketAddress(
                        "localhost", port));
                m_socketChannel.configureBlocking(true);
                m_socket = m_socketChannel.socket();
                m_socket.setTcpNoDelay(true);
            } catch (final Exception e) {
                System.out.println(e.getMessage());
                System.out
                        .println("Failed to initialize IPC EE connection. Quitting.");
                while (true) {}

                //System.exit(-1);
            }
            System.out.println("Created IPC connection for site.");
        }

        /* Close the socket indicating to the EE it should terminate */
        public void close() throws InterruptedException {
            if (m_socketChannel != null) {
                try {
                    m_socketChannel.close();
                } catch (final IOException e) {
                    throw new RuntimeException(e);
                }
                m_socketChannel = null;
                m_socket = null;
            }
            if (m_eeProcess != null) {
                m_eeProcess.waitFor();
            }
            if (m_stdoutParser != null) {
                m_stdoutParser.join();
            }
        }

//        /** blocking write of all m_data to outputstream */
//        void write(final byte data[], final int amount) throws IOException {
//            // write 4 byte length (which includes its own 4 bytes) in big-endian
//            // order. this hurts .. but I'm not sure of a better way.
//            final byte[] length = new byte[4];
//            int amt = amount + 4;
//            // System.out.println("Sending " + amt + " bytes");
//            assert (amt >= 8);
//            for (int i = 3; i >= 0; --i) {
//                length[i] = (byte) (amt & 0xff);
//                amt >>= 8;
//            }
//            m_socket.getOutputStream().write(length);
//            m_socket.getOutputStream().write(data, 0, amount);
//        }

        /** blocking write of all m_data to outputstream */
        void write() throws IOException {
            // write 4 byte length (which includes its own 4 bytes) in big-endian
            // order. this hurts .. but I'm not sure of a better way.
            m_dataNetwork.clear();
            final int amt = m_data.remaining();
            m_dataNetwork.putInt(amt + 4);
            m_dataNetwork.limit(4 + amt);
            m_dataNetwork.rewind();
            while (m_dataNetwork.hasRemaining()) {
                m_socketChannel.write(m_dataNetwork);
            }
        }

        /**
         * An error code specific to the IPC backend that indicates
         * that as part of fulfilling a previous request the IPC
         * backend requires a dependency table. This is not really
         * an error code.
         */
        static final int kErrorCode_RetrieveDependency = 100;

        /**
         * An error code to be sent in a response to an RetrieveDependency request.
         * Indicates that a dependency table was found and that it follows
         */
        static final int kErrorCode_DependencyFound = 101;

        /**
         * An error code to be sent in a response to an RetrieveDependency request.
         * Indicates that no dependency tables could be found and that no data follows.
         */
        static final int kErrorCode_DependencyNotFound = 102 ;

        /**
         * Invoke crash VoltDB
         */
        static final int kErrorCode_CrashVoltDB = 104;

        /**
         * Read a single byte indicating a return code. This method has evolved
         * to include providing dependency tables necessary for the completion of previous
         * request. The method loops ready status bytes instead of recursing to avoid
         * excessive recursion in the case where a large number of dependency tables
         * must be fetched before the request can be satisfied.
         *
         * Facing further evolutionary pressure, readStatusByte has  grown
         * EL buffer reading fins. EL buffers arrive here mid-command execution.
         * @return
         * @throws IOException
         */
        int readStatusByte() throws IOException {
            int status = kErrorCode_RetrieveDependency;

            while (true) {
                status = m_socket.getInputStream().read();
                if (status == kErrorCode_RetrieveDependency) {
                    final ByteBuffer dependencyIdBuffer = ByteBuffer.allocate(4);
                    while (dependencyIdBuffer.hasRemaining()) {
                        final int read = m_socketChannel.read(dependencyIdBuffer);
                        if (read == -1) {
                            throw new IOException("Unable to read enough bytes for dependencyId in order to " +
                            " satisfy IPC backend request for a dependency table");
                        }
                    }
                    dependencyIdBuffer.rewind();
                    sendDependencyTable(dependencyIdBuffer.getInt());
                    continue;
                }
                if (status == kErrorCode_CrashVoltDB) {
                    ByteBuffer lengthBuffer = ByteBuffer.allocate(4);
                    while (lengthBuffer.hasRemaining()) {
                        final int read = m_socket.getChannel().read(lengthBuffer);
                        if (read == -1) {
                            throw new EOFException();
                        }
                    }
                    lengthBuffer.flip();
                    ByteBuffer messageBuffer = ByteBuffer.allocate(lengthBuffer.getInt());
                    while (messageBuffer.hasRemaining()) {
                        final int read = m_socket.getChannel().read(messageBuffer);
                        if (read == -1) {
                            throw new EOFException();
                        }
                    }

                    final int reasonLength = messageBuffer.getInt();
                    final byte reasonBytes[] = new byte[reasonLength];
                    messageBuffer.get(reasonBytes);
                    final String message = new String(reasonBytes, "UTF-8");

                    final int filenameLength = messageBuffer.getInt();
                    final byte filenameBytes[] = new byte[filenameLength];
                    messageBuffer.get(filenameBytes);
                    final String filename = new String(filenameBytes, "UTF-8");

                    final int lineno = messageBuffer.getInt();


                    final int numTraces = messageBuffer.getInt();
                    final String traces[] = new String[numTraces];

                    for (int ii = 0; ii < numTraces; ii++) {
                        final int traceLength = messageBuffer.getInt();
                        final byte traceBytes[] = new byte[traceLength];
                        traces[ii] = new String(traceBytes, "UTF-8");
                    }

                    ExecutionEngine.crashVoltDB(message, traces, filename, lineno);
                }

                try {
                    checkErrorCode(status);
                    break;
                }
                catch (final RuntimeException e) {
                    if (e instanceof SerializableException) {
                        throw e;
                    } else {
                        throw (IOException)e.getCause();
                    }
                }
            }

            return status;
        }


        /**
         * Read and deserialize some number of tables from the wire. Assumes that the message is length prefixed.
         * @param tables Output array as well as indicator of exactly how many tables to read off of the wire
         * @throws IOException
         */
        public void readResultTables(final VoltTable tables[]) throws IOException {
            final ByteBuffer resultTablesLengthBytes = ByteBuffer.allocate(4);

            //resultTablesLengthBytes.order(ByteOrder.LITTLE_ENDIAN);
            while (resultTablesLengthBytes.hasRemaining()) {
                int read = m_socketChannel.read(resultTablesLengthBytes);
                if (read == -1) {
                    throw new EOFException();
                }
            }
            resultTablesLengthBytes.flip();

            final int resultTablesLength = resultTablesLengthBytes.getInt();
            final ByteBuffer resultTablesBuffer = ByteBuffer
                    .allocate(resultTablesLength);
            //resultTablesBuffer.order(ByteOrder.LITTLE_ENDIAN);
            while (resultTablesBuffer.hasRemaining()) {
                int read = m_socketChannel.read(resultTablesBuffer);
                if (read == -1) {
                    throw new EOFException();
                }
            }
            resultTablesBuffer.flip();

            final FastDeserializer ds = new FastDeserializer(resultTablesBuffer);
            // check if anything was changed
            final boolean dirty = ds.readBoolean();
            if (dirty)
                m_dirty = true;

            for (int ii = 0; ii < tables.length; ii++) {
                final int dependencyCount = ds.readInt(); // ignore the table count
                assert(dependencyCount == 1); //Expect one dependency generated per plan fragment
                ds.readInt(); // ignore the dependency ID
                tables[ii] = (VoltTable) ds.readObject(tables[ii], null);
            }
        }

        /**
         * Read the result dependencies returned from the execution of a plan fragment.
         * Returns a list of pairs of dependency ids and dependency tables.
         */
        public DependencySet readDependencies() throws IOException {
            // read the result set size, which doesn't include this 4 byte
            // length notification!
            final ByteBuffer resultSetSizeBuff = ByteBuffer.allocate(4);
            resultSetSizeBuff.rewind();
            while (resultSetSizeBuff.hasRemaining()) {
                int read = m_socketChannel.read(resultSetSizeBuff);
                if (read == -1) {
                    throw new EOFException();
                }
            }

            resultSetSizeBuff.rewind();
            final int resultsSize = resultSetSizeBuff.getInt();

            // read the serialized dependencies
            final ByteBuffer depsBuff = ByteBuffer.allocate(resultsSize);
            depsBuff.clear().rewind();
            while (depsBuff.hasRemaining()) {
                int read = m_socketChannel.read(depsBuff);
                if (read == -1) {
                    throw new EOFException();
                }
            }

            // deserialize the dependencies
            depsBuff.rewind();
            final boolean dirty = depsBuff.get() > 0;
            if (dirty) {
                m_dirty = true;
            }
            final int numDependencies = depsBuff.getInt();
            final int[] depIds = new int[numDependencies];
            final VoltTable[] dependencies = new VoltTable[numDependencies];
            final FastDeserializer fds = new FastDeserializer(depsBuff);
            for (int ii = 0; ii < numDependencies; ++ii) {
                depIds[ii] = fds.readInt();
                dependencies[ii] = fds.readObject(VoltTable.class);
            }
            assert(depIds.length == 1);

            // and finally return the constructed dependency set
            return new DependencySet(depIds, dependencies);
        }

        public void throwException(final int errorCode) throws IOException {
            final ByteBuffer lengthBuffer = ByteBuffer.allocate(4);
            while (lengthBuffer.hasRemaining()) {
                int read = m_socketChannel.read(lengthBuffer);
                if (read == -1) {
                    throw new EOFException();
                }
            }
            lengthBuffer.flip();
            final int exceptionLength = lengthBuffer.getInt();//Length is only between EE and Java.
            if (exceptionLength == 0) {
                throw new EEException(errorCode);
            } else {
                final ByteBuffer exceptionBuffer = ByteBuffer.allocate(exceptionLength + 4);
                exceptionBuffer.putInt(exceptionLength);
                while(exceptionBuffer.hasRemaining()) {
                    int read = m_socketChannel.read(exceptionBuffer);
                    if (read == -1) {
                        throw new EOFException();
                    }
                }
                assert(!exceptionBuffer.hasRemaining());
                exceptionBuffer.rewind();
                throw SerializableException.deserializeFromBuffer(exceptionBuffer);
            }
        }
    }

    /** Local m_data */
    private final int m_clusterIndex;
    private final int m_siteId;
    private final int m_partitionId;
    private final int m_hostId;
    private final String m_hostname;
    // private final FastSerializer m_fser;
    private final Connection m_connection;
    private final BBContainer m_dataNetworkOrigin;
    private final ByteBuffer m_dataNetwork;
    private ByteBuffer m_data;

    // private int m_counter;

    public ExecutionEngineIPC(
            final PartitionExecutor site,
            final int clusterIndex,
            final int siteId,
            final int partitionId,
            final int hostId,
            final String hostname,
            final BackendTarget target) {
        super(site);
        // m_counter = 0;
        m_clusterIndex = clusterIndex;
        m_siteId = siteId;
        m_partitionId = partitionId;
        m_hostId = hostId;
        m_hostname = hostname;
        // m_fser = new FastSerializer(false, false);
        m_connection = new Connection(target);

        // voltdbipc assumes host byte order everywhere
        m_dataNetworkOrigin = org.voltdb.utils.DBBPool.allocateDirect(1024 * 1024 * 10);
        m_dataNetwork = m_dataNetworkOrigin.b;
        m_dataNetwork.position(4);
        m_data = m_dataNetwork.slice();

        initialize(m_clusterIndex, m_siteId, m_partitionId, m_hostId, m_hostname);
    }

    /** Utility method to generate an EEXception that can be overriden by derived classes**/
    @Override
    protected void throwExceptionForError(final int errorCode) {
        try {
            m_connection.throwException(errorCode);
        } catch (final IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void release() throws EEException, InterruptedException {
        m_connection.close();
        m_dataNetworkOrigin.discard();
    }

    /**
     * the abstract api assumes construction initializes but here initialization
     * is just another command.
     */
    public void initialize(
            final int clusterIndex,
            final int siteId,
            final int partitionId,
            final int hostId,
            final String hostname
            ) {
        int result = ExecutionEngine.ERRORCODE_ERROR;
        m_data.clear();
        m_data.putInt(Commands.Initialize.m_id);
        m_data.putInt(clusterIndex);
        m_data.putInt(siteId);
        m_data.putInt(partitionId);
        m_data.putInt(hostId);
        m_data.putLong(EELoggers.getLogLevels());
        m_data.putShort((short)hostname.length());
        try {
            m_data.put(hostname.getBytes("UTF-8"));
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
        try {
            m_data.flip();
            m_connection.write();
            result = m_connection.readStatusByte();
        } catch (final IOException e) {
            System.out.println("Exception: " + e.getMessage());
            throw new RuntimeException(e);
        }
        checkErrorCode(result);
    }

    /** write the catalog as a UTF-8 byte string via connection */
    @Override
    public void loadCatalog(final String serializedCatalog) throws EEException {
        int result = ExecutionEngine.ERRORCODE_ERROR;
        m_data.clear();

        try {
            final byte catalogBytes[] = serializedCatalog.getBytes("UTF-8");
            if (m_data.capacity() < catalogBytes.length + 100) {
                m_data = ByteBuffer.allocate(catalogBytes.length + 100);
            }
            m_data.putInt(Commands.LoadCatalog.m_id);
            m_data.put(catalogBytes);
            m_data.put((byte)'\0');
        } catch (final UnsupportedEncodingException ex) {
            Logger.getLogger(ExecutionEngineIPC.class.getName()).log(
                    Level.SEVERE, null, ex);
        }

        try {
            m_data.flip();
            m_connection.write();
            result = m_connection.readStatusByte();
        } catch (final IOException e) {
            System.out.println("Exception: " + e.getMessage());
            throw new RuntimeException(e);
        }
        checkErrorCode(result);
    }

    /** write the diffs as a UTF-8 byte string via connection */
    @Override
    public void updateCatalog(final String catalogDiffs, int catalogVersion) throws EEException {
        int result = ExecutionEngine.ERRORCODE_ERROR;
        m_data.clear();

        try {
            final byte catalogBytes[] = catalogDiffs.getBytes("UTF-8");
            if (m_data.capacity() < catalogBytes.length + 100) {
                m_data = ByteBuffer.allocate(catalogBytes.length + 100);
            }
            m_data.putInt(Commands.UpdateCatalog.m_id);
            m_data.putInt(catalogVersion);
            m_data.put(catalogBytes);
            m_data.put((byte)'\0');
        } catch (final UnsupportedEncodingException ex) {
            Logger.getLogger(ExecutionEngineIPC.class.getName()).log(
                    Level.SEVERE, null, ex);
        }

        try {
            m_data.flip();
            m_connection.write();
            result = m_connection.readStatusByte();
        } catch (final IOException e) {
            System.out.println("Exception: " + e.getMessage());
            throw new RuntimeException(e);
        }
        checkErrorCode(result);
    }

    @Override
    public void tick(final long time, final long lastCommittedTxnId) {
        int result = ExecutionEngine.ERRORCODE_ERROR;
        m_data.clear();
        m_data.putInt(Commands.Tick.m_id);
        m_data.putLong(time);
        m_data.putLong(lastCommittedTxnId);
        try {
            m_data.flip();
            m_connection.write();
            result = m_connection.readStatusByte();
        } catch (final IOException e) {
            System.out.println("Exception: " + e.getMessage());
            throw new RuntimeException(e);
        }
        checkErrorCode(result);
        // no return code for tick.
    }

    @Override
    public void quiesce(long lastCommittedTransactionId) {
        int result = ExecutionEngine.ERRORCODE_ERROR;
        m_data.clear();
        m_data.putInt(Commands.Quiesce.m_id);
        m_data.putLong(lastCommittedTransactionId);
        try {
            m_data.flip();
            m_connection.write();
            result = m_connection.readStatusByte();
        } catch (final IOException e) {
            System.out.println("Excpeption: " + e.getMessage());
            throw new RuntimeException();
        }
        checkErrorCode(result);
    }

    private void sendPlanFragmentsInvocation(final Commands cmd,
            final long[] planFragmentIds, final int numFragmentIds,
            final int[] input_depIds,
            final int[] output_depIds,
            final ParameterSet[] parameterSets, final int numParameterSets, final long txnId,
            final long lastCommittedTxnId, final long undoToken) {
        // big endian, not direct
        final FastSerializer fser = new FastSerializer();
        try {
            for (int i = 0; i < numFragmentIds; ++i) {
                parameterSets[i].writeExternal(fser);
            }
        } catch (final IOException exception) {
            throw new RuntimeException(exception);
        }

        m_data.clear();
        m_data.putInt(cmd.m_id);
        m_data.putLong(txnId);
        m_data.putLong(lastCommittedTxnId);
        m_data.putLong(undoToken);
        m_data.putInt(numFragmentIds);
        m_data.putInt(numParameterSets);
        for (int i = 0; i < numFragmentIds; ++i) {
            m_data.putLong(planFragmentIds[i]);
        }
        /** PAVLO **/
        for (int i = 0; i < numFragmentIds; ++i) {
            m_data.putInt(input_depIds[i]);
        }
        for (int i = 0; i < numFragmentIds; ++i) {
            m_data.putInt(output_depIds[i]);
        }
        /** PAVLO **/
        m_data.put(fser.getBuffer());

        try {
            m_data.flip();
            m_connection.write();
        } catch (final Exception e) {
            System.out.println("Exception: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }

    @Override
    public DependencySet executePlanFragment(final long planFragmentId, final int outputDepId,
            final int inputDepId, final ParameterSet parameterSet, final long txnId,
            final long lastCommittedTxnId, final long undoToken)
            throws EEException {
        // big endian, not direct
        final FastSerializer fser = new FastSerializer();
        try {
            parameterSet.writeExternal(fser);
        } catch (final IOException exception) {
            throw new RuntimeException(exception);
        }

        m_data.clear();
        m_data.putInt(Commands.PlanFragment.m_id);
        m_data.putLong(txnId);
        m_data.putLong(lastCommittedTxnId);
        m_data.putLong(undoToken);
        m_data.putLong(planFragmentId);
        m_data.putInt(outputDepId);
        m_data.putInt(inputDepId);
        m_data.put(fser.getBuffer());

        try {
            m_data.flip();
            m_connection.write();
        } catch (final Exception e) {
            System.out.println("Exception: " + e.getMessage());
            throw new RuntimeException(e);
        }

        int result = ExecutionEngine.ERRORCODE_ERROR;
        try {
            result = m_connection.readStatusByte();
        } catch (final IOException e) {
            System.out.println("Exception: " + e.getMessage());
            throw new RuntimeException(e);
        }

        if (result != ExecutionEngine.ERRORCODE_SUCCESS) {
            throw new EEException(result);
        } else {
            try {
                return m_connection.readDependencies();
            } catch (final IOException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }
        return null;
    }

    @Override
    public VoltTable executeCustomPlanFragment(final String plan, int outputDepId,
            int inputDepId, final long txnId, final long lastCommittedTxnId,
            final long undoQuantumToken) throws EEException
    {
        final FastSerializer fser = new FastSerializer();
        try {
            fser.writeString(plan);
        } catch (final IOException exception) {
            throw new RuntimeException(exception);
        }

        m_data.clear();
        m_data.putInt(Commands.CustomPlanFragment.m_id);
        m_data.putLong(txnId);
        m_data.putLong(lastCommittedTxnId);
        m_data.putLong(undoQuantumToken);
        m_data.putInt(outputDepId);
        m_data.putInt(inputDepId);
        m_data.put(fser.getBuffer());

        try {
            m_data.flip();
            m_connection.write();
        } catch (final Exception e) {
            System.out.println("Exception: " + e.getMessage());
            throw new RuntimeException(e);
        }

        int result = ExecutionEngine.ERRORCODE_ERROR;
        try {
            result = m_connection.readStatusByte();
        } catch (final IOException e) {
            System.out.println("Exception: " + e.getMessage());
            throw new RuntimeException(e);
        }
        if (result == ExecutionEngine.ERRORCODE_SUCCESS) {
            final VoltTable resultTables[] = new VoltTable[1];
            resultTables[0] = PrivateVoltTableFactory.createUninitializedVoltTable();
            try {
                m_connection.readResultTables(resultTables);
            } catch (final IOException e) {
                throw new EEException(
                        ExecutionEngine.ERRORCODE_WRONG_SERIALIZED_BYTES);
            }
            return resultTables[0];
        }
        return null;
    }

    @Override
    public DependencySet executeQueryPlanFragmentsAndGetDependencySet(
            long[] planFragmentIds,
            int numFragmentIds,
            int[] input_depIds,
            int[] output_depIds,
            ParameterSet[] parameterSets,
            int numParameterSets,
            long txnId, long lastCommittedTxnId, long undoToken) throws EEException {
        
        // TODO
        return (null);
    }
    
    @Override
    public VoltTable[] executeQueryPlanFragmentsAndGetResults(
            final long[] planFragmentIds, final int numFragmentIds,
            final int[] input_depIds,
            final int[] output_depIds,
            final ParameterSet[] parameterSets, final int numParameterSets, final long txnId,
            final long lastCommittedTxnId, final long undoToken) throws EEException {
        sendPlanFragmentsInvocation(Commands.QueryPlanFragments,
                planFragmentIds, numFragmentIds,
                input_depIds,
                output_depIds,
                parameterSets,
                numParameterSets, txnId, lastCommittedTxnId, undoToken);
        int result = ExecutionEngine.ERRORCODE_ERROR;
        try {
            result = m_connection.readStatusByte();
        } catch (final IOException e) {
            System.out.println("Exception: " + e.getMessage());
            throw new RuntimeException(e);
        }
        if (result == ExecutionEngine.ERRORCODE_SUCCESS) {
            final VoltTable resultTables[] = new VoltTable[numFragmentIds];
            for (int ii = 0; ii < numFragmentIds; ii++) {
                resultTables[ii] = PrivateVoltTableFactory.createUninitializedVoltTable();
            }
            try {
                m_connection.readResultTables(resultTables);
            } catch (final IOException e) {
                throw new EEException(
                        ExecutionEngine.ERRORCODE_WRONG_SERIALIZED_BYTES);
            }
            return resultTables;
        }
        return null;
    }

    @Override
    public VoltTable serializeTable(final int tableId) throws EEException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    /**
     * Unsupported implementation of toggleProfiler
     */
    @Override
    public int toggleProfiler(final int toggle) {
        throw new UnsupportedOperationException("Not supported yet.");
    }


    @Override
    public void loadTable(final int tableId, final VoltTable table, final long txnId,
            final long lastCommittedTxnId, final long undoToken, boolean allowExport)
        throws EEException
    {
        m_data.clear();
        m_data.putInt(Commands.LoadTable.m_id);
        m_data.putInt(tableId);
        m_data.putLong(txnId);
        m_data.putLong(lastCommittedTxnId);
        m_data.putLong(undoToken);

        if(allowExport)
            m_data.putShort((short) 1);
        else
            m_data.putShort((short) 0);

        final ByteBuffer tableBytes = table.getTableDataReference();
        if (m_data.remaining() < tableBytes.remaining()) {
            m_data.flip();
            final ByteBuffer newBuffer = ByteBuffer.allocate(m_data.remaining()
                    + tableBytes.remaining());
            newBuffer.rewind();
            //newBuffer.order(ByteOrder.LITTLE_ENDIAN);
            newBuffer.put(m_data);
            m_data = newBuffer;
        }
        m_data.put(tableBytes);

        try {
            m_data.flip();
            m_connection.write();
        } catch (final Exception e) {
            System.out.println("Exception: " + e.getMessage());
            throw new RuntimeException(e);
        }

        int result = ExecutionEngine.ERRORCODE_ERROR;
        try {
            result = m_connection.readStatusByte();
        } catch (final IOException e) {
            System.out.println("Exception: " + e.getMessage());
            throw new RuntimeException(e);
        }

        if (result != ExecutionEngine.ERRORCODE_SUCCESS) {
            throw new EEException(result);
        }
    }

    @Override
    public VoltTable[] getStats(
            final SysProcSelector selector,
            final int[] locators,
            final boolean interval,
            final Long now) {
        m_data.clear();
        m_data.putInt(Commands.GetStats.m_id);
        m_data.putInt(selector.ordinal());
        if (interval) {
            m_data.put((byte)1);
        } else {
            m_data.put((byte)0);
        }
        m_data.putLong(now);
        m_data.putInt(locators.length);
        for (final int locator : locators) {
            m_data.putInt(locator);
        }

        m_data.flip();
        try {
            m_connection.write();
        } catch (final IOException e) {
            System.out.println("Exception: " + e.getMessage());
            throw new RuntimeException(e);
        }

        int result = ExecutionEngine.ERRORCODE_ERROR;
        try {
            result = m_connection.readStatusByte();
        } catch (final IOException e) {
            System.out.println("Exception: " + e.getMessage());
            throw new RuntimeException(e);
        }

        try {
            if (result == ExecutionEngine.ERRORCODE_SUCCESS) {
                final ByteBuffer messageLengthBuffer = ByteBuffer.allocate(4);
                while (messageLengthBuffer.hasRemaining()) {
                    int read = m_connection.m_socketChannel.read(messageLengthBuffer);
                    if (read == -1) {
                        throw new EOFException();
                    }
                }
                messageLengthBuffer.rewind();
                final ByteBuffer messageBuffer = ByteBuffer.allocate(messageLengthBuffer.getInt());
                while (messageBuffer.hasRemaining()) {
                    int read = m_connection.m_socketChannel.read(messageBuffer);
                    if (read == -1) {
                        throw new EOFException();
                    }
                }
                messageBuffer.rewind();

                final FastDeserializer fds = new FastDeserializer(messageBuffer);
                final VoltTable results[] = new VoltTable[1];
                final VoltTable resultTable = PrivateVoltTableFactory.createUninitializedVoltTable();
                results[0] = (VoltTable)fds.readObject(resultTable, this);
                return results;
            }
        } catch (final IOException e) {
            System.out.println("Exception: " + e.getMessage());
            throw new RuntimeException(e);
        }
        return null;
    }

    @Override
    public boolean releaseUndoToken(final long undoToken) {
        m_data.clear();
        m_data.putInt(Commands.releaseUndoToken.m_id);
        m_data.putLong(undoToken);

        try {
            m_data.flip();
            m_connection.write();
        } catch (final Exception e) {
            System.out.println("Exception: " + e.getMessage());
            throw new RuntimeException(e);
        }

        int result = ExecutionEngine.ERRORCODE_ERROR;
        try {
            result = m_connection.readStatusByte();
        } catch (final IOException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }

        if (result != ExecutionEngine.ERRORCODE_SUCCESS) {
            return false;
        }
        return true;
    }

    @Override
    public boolean undoUndoToken(final long undoToken) {
        m_data.clear();
        m_data.putInt(Commands.undoUndoToken.m_id);
        m_data.putLong(undoToken);

        try {
            m_data.flip();
            m_connection.write();
        } catch (final Exception e) {
            System.out.println("Exception: " + e.getMessage());
            throw new RuntimeException(e);
        }

        int result = ExecutionEngine.ERRORCODE_ERROR;
        try {
            result = m_connection.readStatusByte();
        } catch (final Exception e) {
            System.out.println("Exception: " + e.getMessage());
            throw new RuntimeException(e);
        }

        if (result != ExecutionEngine.ERRORCODE_SUCCESS) {
            return false;
        }
        return true;
    }

    @Override
    public boolean setLogLevels(final long logLevels) throws EEException {
        m_data.clear();
        m_data.putInt(Commands.SetLogLevels.m_id);
        m_data.putLong(logLevels);

        try {
            m_data.flip();
            m_connection.write();
        } catch (final Exception e) {
            System.out.println("Exception: " + e.getMessage());
            throw new RuntimeException(e);
        }

        int result = ExecutionEngine.ERRORCODE_ERROR;
        try {
            result = m_connection.readStatusByte();
        } catch (final IOException e) {
            System.out.println("Exception: " + e.getMessage());
            throw new RuntimeException(e);
        }

        if (result != ExecutionEngine.ERRORCODE_SUCCESS) {
            return false;
        }
        return true;
    }

    /**
     * Retrieve a dependency table and send it via the connection. If
     * no table is available send a response code indicating such.
     * The message is prepended with two lengths. One length is for
     * the network layer and is the size of the whole message not including
     * the length prefix.
     * @param dependencyId ID of the dependency table to send to the client
     */
    private void sendDependencyTable(final int dependencyId) throws IOException{
        final byte[] dependencyBytes = nextDependencyAsBytes(dependencyId);
        if (dependencyBytes == null) {
            m_connection.m_socket.getOutputStream().write(Connection.kErrorCode_DependencyNotFound);
            return;
        }
        // 1 for response code + 4 for dependency length prefix + dependencyBytes.length
        final ByteBuffer message = ByteBuffer.allocate(1 + 4 + dependencyBytes.length);

        // write the response code
        message.put((byte)Connection.kErrorCode_DependencyFound);

        // write the dependency's length prefix
        message.putInt(dependencyBytes.length);

        // finally, write dependency table itself
        message.put(dependencyBytes);
        message.rewind();
        if (m_connection.m_socketChannel.write(message) != message.capacity()) {
            throw new IOException("Unable to send dependency table to client. Attempted blocking write of " +
                    message.capacity() + " but not all of it was written");
        }
    }

    @Override
    public boolean activateTableStream(int tableId, TableStreamType streamType) {
        m_data.clear();
        m_data.putInt(Commands.ActivateTableStream.m_id);
        m_data.putInt(tableId);
        m_data.putInt(streamType.ordinal());

        try {
            m_data.flip();
            m_connection.write();
        } catch (final Exception e) {
            System.out.println("Exception: " + e.getMessage());
            throw new RuntimeException(e);
        }

        int result = ExecutionEngine.ERRORCODE_ERROR;
        try {
            result = m_connection.readStatusByte();
        } catch (final Exception e) {
            System.out.println("Exception: " + e.getMessage());
            throw new RuntimeException(e);
        }

        if (result != ExecutionEngine.ERRORCODE_SUCCESS) {
            return false;
        }
        return true;
    }

    @Override
    public int tableStreamSerializeMore(BBContainer c, int tableId, TableStreamType streamType) {
        int bytesReturned = -1;
        ByteBuffer view = c.b.duplicate();
        try {
            m_data.clear();
            m_data.putInt(Commands.TableStreamSerializeMore.m_id);
            m_data.putInt(tableId);
            m_data.putInt(streamType.ordinal());
            m_data.putInt(c.b.remaining());

            m_data.flip();
            m_connection.write();

            m_connection.readStatusByte();

            ByteBuffer lengthBuffer = ByteBuffer.allocate(4);
            while (lengthBuffer.hasRemaining()) {
                int read = m_connection.m_socketChannel.read(lengthBuffer);
                if (read == -1) {
                    throw new EOFException();
                }
            }
            lengthBuffer.flip();
            final int length = lengthBuffer.getInt();
            bytesReturned = length;
            /*
             * Error or no more tuple data for this table.
             */
            if (length == -1 || length == 0) {
                return length;
            }
            view.limit(view.position() + length);
            while (view.hasRemaining()) {
                m_connection.m_socketChannel.read(view);
            }
        } catch (final IOException e) {
            System.out.println("Exception: " + e.getMessage());
            throw new RuntimeException(e);
        }

        return bytesReturned;
    }

    @Override
    public ExportProtoMessage exportAction(boolean ackAction, boolean pollAction,
            boolean resetAction, boolean syncAction,
            long ackOffset, long seqNo, int partitionId, long mTableId) {
        try {
            m_data.clear();
            m_data.putInt(Commands.ExportAction.m_id);
            m_data.putInt(ackAction ? 1 : 0);
            m_data.putInt(pollAction ? 1 : 0);
            m_data.putInt(resetAction ? 1 : 0);
            m_data.putInt(syncAction ? 1 : 0);
            m_data.putLong(ackOffset);
            m_data.putLong(seqNo);
            m_data.putLong(mTableId);
            m_data.flip();
            m_connection.write();

            ByteBuffer data = null;
            ByteBuffer results = ByteBuffer.allocate(8);
            while (results.remaining() > 0)
                m_connection.m_socketChannel.read(results);
            results.flip();
            long result_offset = results.getLong();
            if (result_offset < 0) {
                ExportProtoMessage reply = null;
                reply = new ExportProtoMessage(partitionId, mTableId);
                reply.error();
                return reply;
            }
            else {
                results = ByteBuffer.allocate(4);
                while (results.remaining() > 0)
                    m_connection.m_socketChannel.read(results);
                results.flip();
                int result_sz = results.getInt();
                data = ByteBuffer.allocate(result_sz + 4);
                data.putInt(result_sz);
                while (data.remaining() > 0)
                    m_connection.m_socketChannel.read(data);
                data.flip();

                ExportProtoMessage reply = null;
                if (pollAction) {
                    reply = new ExportProtoMessage(partitionId, mTableId);
                    reply.pollResponse(result_offset, data);
                }
                return reply;
            }

        } catch (final IOException e) {
            throw new RuntimeException(e);
        }

    }

    @Override
    public void processRecoveryMessage( ByteBuffer buffer, long pointer) {
        try {
            m_data.clear();
            m_data.putInt(Commands.RecoveryMessage.m_id);
            m_data.putInt(buffer.remaining());
            m_data.put(buffer);

            m_data.flip();
            m_connection.write();

            m_connection.readStatusByte();
        } catch (final IOException e) {
            System.out.println("Exception: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }

    @Override
    public long tableHashCode(int tableId) {
        try {
            m_data.clear();
            m_data.putInt(Commands.TableHashCode.m_id);
            m_data.putInt(tableId);

            m_data.flip();
            m_connection.write();

            m_connection.readStatusByte();
            ByteBuffer hashCode = ByteBuffer.allocate(8);
            while (hashCode.hasRemaining()) {
                int read = m_connection.m_socketChannel.read(hashCode);
                if (read <= 0) {
                    throw new EOFException();
                }
            }
            hashCode.flip();
            return hashCode.getLong();
        } catch (final IOException e) {
            System.out.println("Exception: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }

    @Override
    public int hashinate(Object value, int partitionCount)
    {
        ParameterSet parameterSet = new ParameterSet(true);
        parameterSet.setParameters(value);

        final FastSerializer fser = new FastSerializer();
        try {
            parameterSet.writeExternal(fser);
        } catch (final IOException exception) {
            throw new RuntimeException(exception);
        }

        m_data.clear();
        m_data.putInt(Commands.Hashinate.m_id);
        m_data.putInt(partitionCount);
        m_data.put(fser.getBuffer());
        try {
            m_data.flip();
            m_connection.write();

            m_connection.readStatusByte();
            ByteBuffer part = ByteBuffer.allocate(4);
            while (part.hasRemaining()) {
                int read = m_connection.m_socketChannel.read(part);
                if (read <= 0) {
                    throw new EOFException();
                }
            }
            part.flip();
            return part.getInt();
        } catch (final Exception e) {
            System.out.println("Exception: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }

    @Override
    public void trackingEnable(Long txnId) throws EEException {
        throw new NotImplementedException("Read/Write Set Tracking is disabled for IPC ExecutionEngine");
    }
    @Override
    public void trackingFinish(Long txnId) throws EEException {
        throw new NotImplementedException("Read/Write Set Tracking is disabled for IPC ExecutionEngine");
    }
    @Override
    public VoltTable trackingReadSet(Long txnId) throws EEException {
        throw new NotImplementedException("Read/Write Set Tracking is disabled for IPC ExecutionEngine");
    }
    @Override
    public VoltTable trackingWriteSet(Long txnId) throws EEException {
        throw new NotImplementedException("Read/Write Set Tracking is disabled for IPC ExecutionEngine");
    }
    
    @Override
    public void antiCacheInitialize(File dbFilePath, long blockSize) throws EEException {
        throw new NotImplementedException("Anti-Caching is disabled for IPC ExecutionEngine");
    }

    @Override
    public void antiCacheReadBlocks(Table catalog_tbl, short[] block_ids, int[] tuple_offsets) {
        throw new NotImplementedException("Anti-Caching is disabled for IPC ExecutionEngine");
    }

    @Override
    public void antiCacheMergeBlocks(Table catalog_tbl) {
        throw new NotImplementedException("Anti-Caching is disabled for IPC ExecutionEngine");
    }

    @Override
    public VoltTable antiCacheEvictBlock(Table catalog_tbl, long block_size, int num_blocks) {
        throw new NotImplementedException("Anti-Caching is disabled for IPC ExecutionEngine");
    }
    
    @Override
    public void MMAPInitialize(File dbDir, long mapSize, long syncFrequency) throws EEException {
        throw new NotImplementedException("Storage MMAP is disabled for IPC ExecutionEngine");
    }
    
    // ARIES 
    @Override
    public void ARIESInitialize(File dbDir, File logFile) throws EEException {
        throw new NotImplementedException("ARIES recovery is disabled for IPC ExecutionEngine");
    }
    
    @Override
    public long getArieslogBufferLength() {
        // XXX: do nothing, we only implement this for JNI now.
        throw new NotImplementedException("ARIES recovery is disabled for IPC ExecutionEngine");
    }

    @Override
    public void getArieslogData(int bufferLength, byte[] arieslogDataArray) {
        throw new NotImplementedException("ARIES recovery is disabled for IPC ExecutionEngine");
    }

    @Override
    public void doAriesRecoveryPhase(long replayPointer, long replayLogSize, long replayTxnId) {
        throw new NotImplementedException("ARIES recovery is disabled for IPC ExecutionEngine");
    }

    @Override
    public void freePointerToReplayLog(long ariesReplayPointer) {
        throw new NotImplementedException("ARIES recovery is disabled for IPC ExecutionEngine");
    }   

    @Override
    public long readAriesLogForReplay(long[] size) {
        throw new NotImplementedException("ARIES recovery is disabled for IPC ExecutionEngine");
    }

    @Override
    public long extractTable(Table extractTable, String destinationShim, String destinationFile, boolean caching) {
        throw new NotImplementedException("ExtractTable is disabled for IPC ExecutionEngine");
    }
    
    @Override
    public long loadTableFromFile(Table loadTable, String destinationShim, String destinationFile) {
        throw new NotImplementedException("LoadTable is disabled for IPC ExecutionEngine");
    }
    
}
