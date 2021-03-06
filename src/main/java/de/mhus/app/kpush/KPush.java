package de.mhus.app.kpush;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;

import de.mhus.lib.core.M;
import de.mhus.lib.core.MArgs;
import de.mhus.lib.core.MCollection;
import de.mhus.lib.core.MDate;
import de.mhus.lib.core.MFile;
import de.mhus.lib.core.MLog;
import de.mhus.lib.core.MPeriod;
import de.mhus.lib.core.MString;
import de.mhus.lib.core.console.Console;
import de.mhus.lib.core.console.ConsoleTable;
import de.mhus.lib.core.node.INode;
import de.mhus.lib.core.node.INodeFactory;
import de.mhus.lib.core.util.Value;

public class KPush extends MLog {

    private File configDir;
    private ArrayList<Job> jobs = new ArrayList<>();
    private MArgs args;
    private String homeDir;
    private int interval;
    private String[] jobFilter;
    
    public void init() throws Exception {
        
        interval = M.to(getArguments().getOption("i").getValue(), 5000 );
        jobFilter = getArguments().getArgument(2).getValues().toArray(new String[0]);
        for (int i = 0; i < jobFilter.length; i++) {
            jobFilter[i] = jobFilter[i].toUpperCase();
            if (jobFilter[i].endsWith(".YAML"))
                jobFilter[i] = MString.beforeLastIndex(jobFilter[i], '.');
        }
        log().d("jobFilter",jobFilter);
        homeDir = System.getenv("KPUSH_HOME");
        if (homeDir == null)
            homeDir = "~/.kpush";
        String configDir = args.getOption("c").getValue();
        if (configDir == null)
            this.configDir = MFile.toFile(homeDir + "/config");
        else
            this.configDir = MFile.toFile(configDir);
        if (!this.configDir.exists())
            log().w("Config directory not found",configDir);
        loadConfig();
        
        
    }

    private void loadConfig() throws Exception {
        if (configDir.isFile()) {
            if (configDir.getName().endsWith(".yaml"))
                loadConfig(configDir);
        } else
            for (File file : configDir.listFiles()) {
                if (!file.isFile() || !file.getName().endsWith(".yaml"))
                    continue;
                
                loadConfig(file);
            }
        jobs.sort(new Comparator<Job>() {
            @Override
            public int compare(Job o1, Job o2) {
                return o1.getName().compareTo(o2.getName());
            }
        });
    }
    
    private Job loadConfig(File file) throws Exception {
        log().d("Load configuration",file);
        INode config = M.l(INodeFactory.class).read(file);
        Job job = new Job(this, config, file);
        if (filterJob(job)) 
            jobs.add(job);
        else
            log().d("ignore by filter",job);
        return job;
    }

    public void push() {
        
        String back = getArguments().getOption("t").getValue();
        if (MString.isSet(back)) {
            final long time = System.currentTimeMillis() - MPeriod.toTime(back, 0);
            log().i("Touch",MDate.toIso8601(time));
            jobs.forEach(j -> j.touchTime(time));
        }
        jobs.forEach(j -> j.push());
    }
    
    public void test() {
        String back = getArguments().getOption("t").getValue();
        Value<Long> time = new Value<>(0l);
        if (MString.isSet(back)) {
            time.value = System.currentTimeMillis() - MPeriod.toTime(back, 0);
            log().i("Since", MDate.toIso8601(time.value));
        }
        jobs.forEach(j -> j.test(time.value));
    }
    
    public void pushAll() {
        jobs.forEach(j -> j.pushAll());
    }
    

    
    public void watch() {
        
        Console console = Console.create();
        System.out.println(console.getClass());
        jobs.forEach(j -> j.startWatch() ); 
        try {
            while (true) {
                console.cleanup();
                console.clearTerminal();
                ConsoleTable table = new ConsoleTable();
                table.fitToConsole();
                table.setHeaderValues("Name", "Left","Watched","Transferred","Errors","Last update");
                
                
                jobs.forEach(j -> table.addRowValues( 
                        j.getName(), 
                        j.getFileToDoCnt(), 
                        j.getFileCnt(), 
                        j.getFileTransferred(), 
                        j.getFileErrors(),
                        j.getLastUpdateStart() 
                        ) );
                table.print();
                
                Thread.sleep(interval);
                
                for (Job job : new ArrayList<>( jobs )) {
                    if (job.isConfigFileRemoved()) {
                        log().i("config removed",job.getName());
                        System.out.println(job.getName() + " removed");
                        job.stopWatch();
                        jobs.remove(job);
                    } else
                    if (job.isConfigFileChanged()) {
                        log().i("config changed",job.getName());
                        System.out.println(job.getName() + " changed");
                        job.stopWatch();
                        jobs.remove(job);
                        try {
                            job = loadConfig(job.getConfigFile());
                            job.startWatch();
                        } catch (Exception e) {
                            log().e(e);
                        }
                        
                    }
                }
                
            }
        } catch (InterruptedException e) {
            System.out.println("Exited");
        }

        jobs.forEach(j -> j.stopWatch() ); 
    }
    
    public void touch() {
        String back = getArguments().getOption("t").getValue();
        final long time = System.currentTimeMillis() - MPeriod.toTime(back, 0);
        jobs.forEach(j -> j.touchTime(time));
    }

    public void reset() {
        jobs.forEach(j -> j.touchTime(0));
    }
    

    private boolean filterJob(Job job) {
        if (jobFilter.length == 0) return true;
        return MCollection.contains(jobFilter, job.getName().toUpperCase());
    }

    public void setArguments(MArgs margs) {
        this.args = margs;
    }
    
    public MArgs getArguments() {
        if (args == null) return new MArgs(null);
        return args;
    }

    public long getInterval() {
        return interval;
    }

    public void showInfo() {
        if (jobFilter.length == 0) {
            jobs.forEach(j -> System.out.println(MDate.toIsoDateTime(j.getLastUpdated()) + " " + j.getName()) );
        } else {
            jobs.forEach(j -> {
                System.out.println("Target: " + j.getName());
                System.out.println("  Last updated: " + MDate.toIsoDateTime(j.getLastUpdated()));
                System.out.println("  File        : " + j.getConfigFile());
                System.out.println("  Namespace   : " + j.getNamespace());
                System.out.println("  Pod         : " + j.getPod());
                System.out.println("  Container   : " + j.getContainer());
            }
            );
            
        }
    }

}
