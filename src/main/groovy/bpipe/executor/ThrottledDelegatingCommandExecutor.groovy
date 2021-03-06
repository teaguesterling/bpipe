package bpipe.executor

import groovy.util.logging.Log;
import bpipe.Command;
import bpipe.Concurrency;
import bpipe.PipelineContext;
import bpipe.ResourceUnit;

/**
 * Wraps another CommandExecutor and adds concurrency control to it
 * so that the command will not execute if it would violate the concurrency 
 * restrictions configured by the user.
 * 
 * @author ssadedin
 */
@Log
class ThrottledDelegatingCommandExecutor {
    
    public static final long serialVersionUID = 4057750835163512598L
    
    /**
     * The level of concurrency that will be reserved for executing the command
     */
    List<ResourceUnit> resources
    
    transient Appendable outputLog = null
    
    /**
     * The output log to which stderr will be written
     */
    transient Appendable errorLog = null
     
    /**
     * If true, command is only actually executed when "waitFor()" is called
     * This is necessary to avoid deadlocks when multiple commands are run
     * simultaneously inside a single resource usage block 
     * (see {@link PipelineContext#multiExec(java.util.List)}
     */
    boolean deferred = false
    
    @Delegate CommandExecutor commandExecutor
    
    // Stored parameters that are cached from the original "start" command
    // and used when "waitFor" is called
    Map cfg
    Command command
    File outputDirectory
    
    ThrottledDelegatingCommandExecutor(CommandExecutor delegateTo, Map resources) {
        
        // Note that the sort here is vital to avoid deadlocks - it ensures 
        // that resources are always allocated in the same order
        this.resources = resources.values()*.clone().sort { it.key }
        this.commandExecutor = delegateTo
    }
    
    /**
     * Acquire the number of configured concurrency permits and then call
     * the delegate {@link CommandExecutor#start(java.util.Map, String, String, String, java.io.File)}
     */
    @Override
    void start(Map cfg, Command cmd, File outputDirectory, Appendable outputLog, Appendable errorLog) {
        this.command = cmd
        this.outputLog = outputLog
        this.errorLog = errorLog
        if(deferred) {
          this.cfg = cfg
          this.outputDirectory = outputDirectory
        }
        else {
            doStart(cfg,cmd,outputDirectory)
        }
    }
    
    void doStart(Map cfg, Command cmd, File outputDirectory) {
        
        ResourceUnit threadResource = resources.find { it.key == "threads" }

        // TODO: test is wrong !
        // == 1 at the end is incorrect, 1 is the default, so this test is trying to check if
        // the user set the value explicitly or not. However they could have actually explicitly set it to 1
        // in which case this will go wonky
        if(threadResource && command.command.contains(PipelineContext.THREAD_LAZY_VALUE) && (threadResource.amount==1) && (threadResource.maxAmount==0))
            threadResource.amount = ResourceUnit.UNLIMITED

        resources.each { Concurrency.instance.acquire(it) }

        int threadCount = threadResource?threadResource.amount:1
        String threadAmount = String.valueOf(threadCount)

        // Problem: some executors use non-integer values here, if we overwrite with an integer value then
        // we break them (Sge)
        if(cfg.procs == null || cfg.procs.toString().isInteger())
            cfg.procs = threadCount

        command.command = command.command.replaceAll(PipelineContext.THREAD_LAZY_VALUE, threadAmount)
        
        command.allocated = true

        bpipe.Pipeline.currentRuntimePipeline.get().isIdle = false

        if(bpipe.Runner.opts.t || bpipe.Config.config.breakTriggered) {
            String msg = command.branch.name ? "Branch $command.branch.name would execute: $cmd.command" : "Would execute $cmd.command"
            this.releaseAll()
            if(commandExecutor instanceof LocalCommandExecutor)
                throw new bpipe.PipelineTestAbort(msg)
            else {
                throw new bpipe.PipelineTestAbort("$msg\n\n                using $commandExecutor with config $cfg")
            }
        }
        commandExecutor.start(cfg, this.command, outputDirectory, outputLog, errorLog)
        
        this.command.save()
    }
    
    /**
     * Wait for the delegate pipeline to stop and then release concurrency permits
     * that were allocated to it
     */
    @Override
    int waitFor() {
        if(deferred) {
            doStart(cfg,this.command,outputDirectory)
        }
        
        try {
            log.info "Waiting for command to complete before releasing ${resources.size()} resources"
            int result = commandExecutor.waitFor()
            return result
        }
        finally {
            log.info "Releasing ${resources.size()} resources"
            releaseAll()
            log.info "Released ${resources.size()} resources"
        }
    }
    
    void releaseAll() {
        try {
            bpipe.Pipeline.currentRuntimePipeline.get().isIdle = true
        }
        catch(Exception e) {
            log.severe "Unable to set pipeline to idle after releasing resources"
        }
        
        resources.reverse().each { resource ->
            try {
                Concurrency.instance.release(resource)
            }
            catch(Throwable t) {
                log.warning("Error reported while releasing $resource.amount $resource.key : " + t.toString())
            }
        }
    }
    
    String status() {
        String result = commandExecutor.status()
        try {
            command.status = bpipe.CommandStatus.valueOf(result)
            command.save()
        }
        catch(Exception e) {
            log.warning("Failed to update command status " + command)
        }
        return result   
    }
    
    @Override
    void stop() {
        try {
            releaseAll()
        }
        catch(Throwable t) {
            // Stop can be called from outside the Bpipe instance that is actually running the
            // pipeline, so that will probably generate this error
            log.warning("Error reported while releasing resources $resources : " + t.toString())
        }
        this.commandExecutor.stop()
    }
    
    String statusMessage() {
        this.commandExecutor.statusMessage()
    }
    
}
