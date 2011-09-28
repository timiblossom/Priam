package com.priam.utils;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Timer;
import java.util.TimerTask;

import org.apache.cassandra.config.ConfigurationException;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Charsets;
import com.google.common.collect.Lists;
import com.google.common.io.Files;
import com.netflix.instance.identity.StorageDevice;
import com.priam.conf.IConfiguration;

public class SystemUtils
{
    private static final Logger logger = LoggerFactory.getLogger(SystemUtils.class);

    /**
     * Mounts all the file systems in the volumes map.
     */
    public static void mountAll(Map<String, StorageDevice> volumes) throws IOException, InterruptedException
    {
        for (Entry<String, StorageDevice> entry : volumes.entrySet())
        {
            List<String> command = Lists.newArrayList();
            if (!"root".equals(System.getProperty("user.name")))
            {
                command.add("/usr/bin/sudo");
                command.add("-E");
            }
            command.add("/usr/local/bin/mountvol");
            command.add("-d");
            command.add(entry.getValue().getDevice());
            command.add("-p");
            command.add(entry.getValue().getMountPoint());

            ProcessBuilder mount = new ProcessBuilder(command);
            final Process proc = mount.start();
            new Timer().schedule(new TimerTask()
            {
                @Override
                public void run()
                {
                    proc.destroy();
                }
            }, 60 * 1000); // kill after it doesnt respond in 60 seconds.
            proc.waitFor();
            if (proc.exitValue() == 0)
                logger.info(String.format("Sucessfully executed: %s", StringUtils.join(mount.command(), ",")));
            else
                logErrorStream(proc);

            if (proc.exitValue() == 0)
            {

                List<String> command1 = Lists.newArrayList();
                if (!"root".equals(System.getProperty("user.name")))
                {
                    command1.add("/usr/bin/sudo");
                    command1.add("-E");
                }
                command1.add("/bin/mount");
                command1.add(entry.getValue().getDevice());
                command1.add(entry.getValue().getMountPoint());

                ProcessBuilder mount1 = new ProcessBuilder(command);
                final Process proc1 = mount1.start();
                new Timer().schedule(new TimerTask()
                {
                    @Override
                    public void run()
                    {
                        proc1.destroy();
                    }
                }, 60 * 1000); // kill after it doesnt respond in 60 seconds.
                proc1.waitFor();
                if (proc1.exitValue() == 0)
                    logger.info(String.format("Sucessfully executed: %s", StringUtils.join(mount1.command(), ",")));
                else
                    logErrorStream(proc1);
            }
        }
    }

    /**
     * Start cassandra process from this co-process.
     */
    public static void startCassandra(boolean join_ring, IConfiguration config) throws IOException, InterruptedException
    {
        logger.info("Starting cassandra server ....Join ring=" + join_ring);
        
        List<String> command = Lists.newArrayList();
        if (!"root".equals(System.getProperty("user.name")))
        {
            command.add("/usr/bin/sudo");
            command.add("-E");
        }
        command.add("/apps/nfcassandra_server/netflix_launch_cassandra.sh");
        ProcessBuilder startCass = new ProcessBuilder(command);
        Map<String, String> env = startCass.environment();
        env.put("HEAP_NEWSIZE", config.getHeapNewSize());
        env.put("MAX_HEAP_SIZE", config.getHeapSize());
        env.put("DATA_DIR", config.getDataFileLocation());
        env.put("COMMIT_LOG_DIR", config.getCommitLogLocation());
        env.put("LOCAL_BACKUP_DIR", config.getBackupLocation());
        env.put("CACHE_DIR", config.getCacheLocation());
        env.put("JMX_PORT", "" + config.getJmxPort());
        env.put("cassandra.join_ring", join_ring ? "true" : "false");
        startCass.directory(new File("/"));
        startCass.redirectErrorStream(true);
        startCass.start();
        logger.info("Starting cassandra server ....");
    }

    /**
     * Stop cassandra process from this co-process.
     */
    public static boolean stopCassandra()
    {
        logger.info("Stopping cassandra server ....");
        Process child = null;
        try
        {
            String command = "/usr/bin/sudo -E /apps/nfcassandra_server/bin/kill_agent.sh cass";
            child = Runtime.getRuntime().exec(command);
            int returnval = child.waitFor();
            if (returnval == 0)
            {
                logger.info("Cassandra stopped");
                return true;
            }
            else
            {
                logger.info("Could not stop cassandra. Unable to run kill_agent");
                return false;
            }

        }
        catch (Exception e)
        {
            logger.error(e.getMessage());
            return false;
        }
    }

    public static int runSysCommand(String command) throws InterruptedException, IOException
    {
        logger.info("Running command " + command.toString());
        // Run backup_cron.sh command
        Process p = Runtime.getRuntime().exec(command.toString());

        StreamReader stdError = new StreamReader(p.getErrorStream(), StreamReader.ERROR);
        StreamReader stdInput = new StreamReader(p.getInputStream(), StreamReader.INFO);
        stdError.start();
        stdInput.start();

        int exitVal = p.waitFor();
        logger.info("Done sys command Exitval: " + exitVal);
        return exitVal;

    }

    public static int runHealthCheck(IConfiguration config) throws InterruptedException, IOException
    {
        String command = "/apps/nfcassandra_server/bin/nodetool -h localhost -p " + config.getJmxPort() + " info";
        logger.info("Running nodetool " + command);
        // Run backup_cron.sh command
        Process p = Runtime.getRuntime().exec(command);

        StreamReader stdError = new StreamReader(p.getErrorStream(), StreamReader.ERROR);
        StreamReader stdInput = new StreamReader(p.getInputStream(), StreamReader.INFO);
        stdError.start();
        stdInput.start();

        int exitVal = p.waitFor();
        logger.info("Done running nodetool " + exitVal);
        return exitVal;

    }

    /**
     * delete all the files/dirs in the given Directory but dont delete the dir
     * itself.
     * @throws IOException 
     */
    public static void cleanupDir(String dirPath) throws IOException
    {
        FileUtils.cleanDirectory(new File(dirPath));
    }

    public static void logErrorStream(Process proc)
    {
        InputStream iError = proc.getErrorStream();
        BufferedReader bfr = new BufferedReader(new InputStreamReader(iError));
        String line = null;
        try
        {
            while ((line = bfr.readLine()) != null)
                logger.info(line);
        }
        catch (IOException e)
        {
            logger.error("IOE: ", e);
        }
        finally
        {
            IOUtils.closeQuietly(iError);
            IOUtils.closeQuietly(bfr);
        }
    }

    public static void createDirs(String location)
    {
        File dirFile = new File(location);
        if (dirFile.exists() && dirFile.isFile())
        {
            dirFile.delete();
            dirFile.mkdirs();
        }
        else if (!dirFile.exists())
            dirFile.mkdirs();
    }

    /**
     * Create a hash of the String which will be an absolute value...
     */
    public static int hash(String string)
    {
        int hash = string.hashCode();
        return Math.abs(hash);
    }

    public static byte[] md5(byte[] buf)
    {
        try
        {
            MessageDigest mdigest = MessageDigest.getInstance("MD5");
            mdigest.update(buf, 0, buf.length);
            return mdigest.digest();
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    /**
     * Get a Md5 string which is similar to OS Md5sum
     */
    public static String md5(File file)
    {
        try
        {
            byte[] digest = Files.getDigest(file, MessageDigest.getInstance("MD5"));
            return toHex(digest);
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    public static String toHex(byte[] digest)
    {
        StringBuffer sb = new StringBuffer(digest.length * 2);
        for (int i = 0; i < digest.length; i++)
        {
            String hex = Integer.toHexString(digest[i]);
            if (hex.length() == 1)
            {
                sb.append("0");
            }
            else if (hex.length() == 8)
            {
                hex = hex.substring(6);
            }
            sb.append(hex);
        }
        return sb.toString().toLowerCase();
    }

    public static String toBase64(byte[] md5)
    {
        byte encoded[] = Base64.encodeBase64(md5, false);
        return new String(encoded);
    }

    /**
     * copy the input to the output.
     */
    public static void copyAndClose(InputStream input, OutputStream output) throws IOException
    {
        try
        {
            IOUtils.copy(input, output);
        }
        finally
        {
            IOUtils.closeQuietly(input);
            IOUtils.closeQuietly(output);
        }
    }

    public static String apiCall(String url)
    {
        try
        {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            if (conn.getResponseCode() != 200)
                throw new ConfigurationException("Ec2Snitch was unable to execute the API call. Not an ec2 node?");

            // Read the information. I wish I could say (String) conn.getContent() here...
            int cl = conn.getContentLength();
            byte[] b = new byte[cl];
            DataInputStream d = new DataInputStream((FilterInputStream) conn.getContent());
            d.readFully(b);
            String return_ = new String(b, Charsets.UTF_8);
            logger.info("Calling URL API: {} returns: {}", url, return_);
            conn.disconnect();
            return return_;
        }
        catch (Exception ex)
        {
            throw new RuntimeException(ex);
        }
    }

    public static File[] sortByLastModifiedTime(File[] files)
    {
        Arrays.sort(files, new Comparator<File>()
        {
            public int compare(File file1, File file2)
            {
                return Long.valueOf(file2.lastModified()).compareTo(Long.valueOf(file1.lastModified()));
            }
        });
        return files;
    }
    
    public static class StreamReader extends Thread
    {
        public static final String ERROR = "ERROR";
        public static final String INFO = "INFO";
        private StringBuilder output = new StringBuilder();
        private InputStream is;
        private String type;

        public StreamReader(InputStream is, String type)
        {
            this.is = is;
            this.type = type;
        }

        public void run()
        {
            try
            {
                InputStreamReader isr = new InputStreamReader(is);
                BufferedReader br = new BufferedReader(isr);
                String line = null;
                while ((line = br.readLine()) != null)
                {
                    output.append(line + "\n");
                    if (type == INFO)
                        logger.info(line);
                    else
                        logger.error(line);
                }
                br.close();
                logger.info("Done running sys command" + output.toString());
            }
            catch (IOException ioe)
            {
                logger.error("Error in running sys command: ", ioe);
            }
        }
        
        public String getOutput()
        {
            return output.toString();
        }
    }

}