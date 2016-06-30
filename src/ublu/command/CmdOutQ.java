    /*
 * Copyright (c) 2014, Absolute Performance, Inc. http://www.absolute-performance.com
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * * Redistributions of source code must retain the above copyright notice, this
 *   list of conditions and the following disclaimer.
 * * Redistributions in binary form must reproduce the above copyright notice,
 *   this list of conditions and the following disclaimer in the documentation
 *   and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package ublu.command;

import ublu.AS400Factory;
import ublu.util.ArgArray;
import ublu.util.DataSink;
import ublu.util.Tuple;
import com.ibm.as400.access.AS400Exception;
import com.ibm.as400.access.AS400SecurityException;
import com.ibm.as400.access.ErrorCompletingRequestException;
import com.ibm.as400.access.ObjectDoesNotExistException;
import com.ibm.as400.access.OutputQueue;
import com.ibm.as400.access.PrintParameterList;
import com.ibm.as400.access.RequestNotSupportedException;
import java.beans.PropertyVetoException;
import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Field;
import java.sql.SQLException;
import java.util.logging.Level;

/**
 * Command to manipulate output queues
 *
 * @author jwoehr
 */
public class CmdOutQ extends Command {

    {
        setNameAndDescription("outq",
                "/4? [-as400 @as400] [-outq @outqueue] [-to @var] [-from @qnamevar] [-clear [[user jobuser] | [form formtype] | all]] | [-hold] | [-instance] | [-noop] | [-release] | [-info] | [-infoparm ATTR]] outputqueuename system user password : operate on output queues");
    }

    /**
     * What we do to the Q
     */
    public enum FUNCTIONS {

        /**
         * Clear the Q
         */
        CLEAR,
        /**
         * Hold the Q
         */
        HOLD,
        /**
         * Put the Q instance
         */
        INSTANCE,
        /**
         * Release the Q
         */
        RELEASE,
        /**
         * Just put the Tuple so this is the instancer with a -to and otherwise
         * it dumps the object
         */
        INFO,
        /**
         * info on a specific parameter
         */
        INFOPARM,
        /**
         * do nothing
         */
        NOOP
    }

    /**
     * Operate on OutputQueues
     *
     * @param argArray arguments in interpreter buffer
     * @return what's left of arguments
     */
    public ArgArray outqueue(ArgArray argArray) {
        FUNCTIONS function = FUNCTIONS.INSTANCE;
        String clearOptName = "";
        String clearOptValue = "";
        Tuple outQTuple = null;
        String infoparm = "";
        while (argArray.hasDashCommand()) {
            String dashCommand = argArray.parseDashCommand();
            switch (dashCommand) {
                case "-as400":
                    setAs400(getAS400Tuple(argArray.next()));
                    break;
                case "-to":
                    String destName = argArray.next();
                    setDataDest(DataSink.fromSinkName(destName));
                    break;
                case "-from":
                    String srcName = argArray.next();
                    setDataSrc(DataSink.fromSinkName(srcName));
                    break;
                case "-clear":
                    function = FUNCTIONS.CLEAR;
                    clearOptName = argArray.nextMaybeTupleString();
                    if (!clearOptName.equals("all")) {
                        clearOptValue = argArray.nextMaybeTupleString();
                    }
                    break;
                case "-noop":
                    function = FUNCTIONS.NOOP;
                    break;
                case "--":
                case "-outq":
                    outQTuple = getTuple(argArray.next());
                    break;
                case "-hold":
                    function = FUNCTIONS.HOLD;
                    break;
                case "-instance":
                    function = FUNCTIONS.INSTANCE;
                    break;
                case "-info":
                    function = FUNCTIONS.INFO;
                    break;
                case "-infoparm":
                    function = FUNCTIONS.INFOPARM;
                    infoparm = argArray.next();
                    break;
                case "-release":
                    function = FUNCTIONS.RELEASE;
                    break;
                default:
                    unknownDashCommand(dashCommand);
            }
        }
        if (havingUnknownDashCommand()) {
            setCommandResult(COMMANDRESULT.FAILURE);
        } else {
            OutputQueue myQ = null;
            if (outQTuple != null) {
                Object tupleValue = outQTuple.getValue();
                if (tupleValue instanceof OutputQueue) {
                    myQ = OutputQueue.class.cast(tupleValue);
                } else {
                    getLogger().log(Level.SEVERE, "Valued tuple which is not an OutputQueue tuple provided to -outq in {0}", getNameAndDescription());
                    setCommandResult(COMMANDRESULT.FAILURE);
                }
            } else { // no provided OutputQueue instance
                switch (getDataSrc().getType()) {
                    case TUPLE:
                        String tuplename = getDataSrc().getName();
                        Tuple t = getTuple(tuplename);
                        if (!(t == null)) {
                            Object o = t.getValue();
                            if (o instanceof String) {
                                myQ = new OutputQueue(getAs400(), o.toString());
                            } else {
                                getLogger().log(Level.SEVERE, "Tuple was not a string for OutputQueue name in {0}", getNameAndDescription());
                                setCommandResult(COMMANDRESULT.FAILURE);
                            }
                        } else {
                            getLogger().log(Level.SEVERE, "Tuple {0} does not exist to specify OutputQueue name in {1}", new Object[]{tuplename, getNameAndDescription()});
                            setCommandResult(COMMANDRESULT.FAILURE);
                        }
                        break;
                    case FILE:
                        String filename = getDataSrc().getName();
                        try {
                            FileReader fr = new FileReader(filename);
                            BufferedReader br = new BufferedReader(fr);
                            String outQName = br.readLine();
                            myQ = new OutputQueue(getAs400(), outQName);
                        } catch (FileNotFoundException ex) {
                            getLogger().log(Level.SEVERE, "Couldn't get OutputQueue from file " + filename, ex);
                            setCommandResult(COMMANDRESULT.FAILURE);
                        } catch (IOException ex) {
                            getLogger().log(Level.SEVERE, "Couldn't get OutputQueue from file " + filename, ex);
                            setCommandResult(COMMANDRESULT.FAILURE);
                        }
                        break;
                    case STD:
                        if (argArray.size() < 1) {
                            logArgArrayTooShortError(argArray);
                            setCommandResult(COMMANDRESULT.FAILURE);
                        } else { // get the Job factors
                            String outQName = argArray.next();
                            if (getAs400() == null) { // no AS400 instance
                                if (argArray.size() < 3) {
                                    logArgArrayTooShortError(argArray);
                                    setCommandResult(COMMANDRESULT.FAILURE);
                                } else { // Get the AS400 instance
                                    String system = argArray.next();
                                    String userid = argArray.next();
                                    String password = argArray.next();
                                    try {
                                        setAs400(AS400Factory.newAS400(getInterpreter(), system, userid, password));
                                    } catch (PropertyVetoException ex) {
                                        getLogger().log(Level.SEVERE, "Couldn't create AS400 instance in " + getNameAndDescription(), ex);
                                        setCommandResult(COMMANDRESULT.FAILURE);
                                    }
                                }
                            }
                            if (getAs400() != null) {
                                myQ = new OutputQueue(getAs400(), outQName);
                            }
                        }
                        break;
                }
            }
            if (myQ != null) {
                switch (function) {
                    case CLEAR:
                        PrintParameterList ppl = new PrintParameterList();
                        switch (clearOptName) {
                            case "all":
                                ppl = null;
                                break;
                            case "user":
                                ppl.setParameter(OutputQueue.ATTR_JOBUSER, clearOptValue);
                                break;
                            case "form":
                                ppl.setParameter(OutputQueue.ATTR_FORMTYPE, clearOptValue);
                        }
                        try {
                            myQ.clear(ppl);
                        } catch (AS400Exception | AS400SecurityException | IOException | InterruptedException | RequestNotSupportedException ex) {
                            getLogger().log(Level.SEVERE, "Exception clearing outq " + myQ + " in " + getNameAndDescription(), ex);
                            setCommandResult(COMMANDRESULT.FAILURE);
                        } catch (ErrorCompletingRequestException ex) {
                            getLogger().log(Level.SEVERE, "Exception clearing outq " + myQ + " in " + getNameAndDescription(), ex);
                            setCommandResult(COMMANDRESULT.FAILURE);
                        }
                        break;
                    case HOLD:
                        try {
                            myQ.hold();
                        } catch (AS400Exception | AS400SecurityException | IOException | InterruptedException | RequestNotSupportedException ex) {
                            getLogger().log(Level.SEVERE, "Exception holding outq " + myQ + " in " + getNameAndDescription(), ex);
                            setCommandResult(COMMANDRESULT.FAILURE);
                        } catch (ErrorCompletingRequestException ex) {
                            getLogger().log(Level.SEVERE, "Exception holding outq " + myQ + " in " + getNameAndDescription(), ex);
                            setCommandResult(COMMANDRESULT.FAILURE);
                        }
                        break;
                    case INSTANCE:
                        try {
                            put(myQ);
                        } catch (SQLException | IOException | AS400SecurityException | ErrorCompletingRequestException | InterruptedException | ObjectDoesNotExistException | RequestNotSupportedException ex) {
                            getLogger().log(Level.SEVERE, "Exception holding putting " + myQ + " in " + getNameAndDescription(), ex);
                            setCommandResult(COMMANDRESULT.FAILURE);
                        }
                        break;
                    case INFO:
                        try {
                            put(myQ);
                        } catch (SQLException | RequestNotSupportedException | IOException | AS400SecurityException | ErrorCompletingRequestException | InterruptedException | ObjectDoesNotExistException ex) {
                            getLogger().log(Level.SEVERE, "Exception putting outq info from " + myQ + " in " + getNameAndDescription(), ex);
                            setCommandResult(COMMANDRESULT.FAILURE);
                        }
                        break;
                    case INFOPARM:
                        try {
                            Field f = myQ.getClass().getField(infoparm.trim());
                            int attr = f.getInt(f);
                            put(myQ.getStringAttribute(attr));
                        } catch (SQLException | RequestNotSupportedException | IOException | AS400SecurityException | ErrorCompletingRequestException | InterruptedException | ObjectDoesNotExistException ex) {
                            getLogger().log(Level.SEVERE, "Exception putting outq infoparm from " + myQ + " in " + getNameAndDescription(), ex);
                            setCommandResult(COMMANDRESULT.FAILURE);
                        } catch (NoSuchFieldException ex) {
                            getLogger().log(Level.SEVERE, "Exception getting outq infoparm from " + myQ + " in " + getNameAndDescription(), ex);
                            setCommandResult(COMMANDRESULT.FAILURE);
                        } catch (SecurityException ex) {
                            getLogger().log(Level.SEVERE, "Exception getting outq infoparm from " + myQ + " in " + getNameAndDescription(), ex);
                            setCommandResult(COMMANDRESULT.FAILURE);
                        } catch (IllegalArgumentException ex) {
                            getLogger().log(Level.SEVERE, "Exception getting outq infoparm from " + myQ + " in " + getNameAndDescription(), ex);
                            setCommandResult(COMMANDRESULT.FAILURE);
                        } catch (IllegalAccessException ex) {
                            getLogger().log(Level.SEVERE, "Exception getting outq infoparm from " + myQ + " in " + getNameAndDescription(), ex);
                            setCommandResult(COMMANDRESULT.FAILURE);
                        }
                        break;
                    case NOOP:
                        break;
                    case RELEASE:
                        try {
                            myQ.release();
                        } catch (AS400Exception | AS400SecurityException | IOException | InterruptedException | RequestNotSupportedException ex) {
                            getLogger().log(Level.SEVERE, "Exception releasing outq " + myQ + " in " + getNameAndDescription(), ex);
                            setCommandResult(COMMANDRESULT.FAILURE);
                        } catch (ErrorCompletingRequestException ex) {
                            getLogger().log(Level.SEVERE, "Exception releasing outq " + myQ + " in " + getNameAndDescription(), ex);
                            setCommandResult(COMMANDRESULT.FAILURE);
                        }
                        break;
                }
            } else {
                getLogger().log(Level.SEVERE, "Unable to get Output Queue instance in {0}", getNameAndDescription());
                setCommandResult(COMMANDRESULT.FAILURE);
            }
        }
        return argArray;
    }

    @Override
    public ArgArray cmd(ArgArray args) {
        reinit();
        return outqueue(args);
    }

    @Override
    public COMMANDRESULT getResult() {
        return getCommandResult();
    }
}
