/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package org.jenkinsci.plugins;

import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Descriptor.FormException;
import hudson.model.Hudson;
import hudson.model.Slave;
import hudson.model.TaskListener;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.ComputerListener;
import hudson.slaves.Cloud;
import hudson.slaves.RetentionStrategy;
import hudson.slaves.NodeProperty;
import hudson.slaves.SlaveComputer;
import hudson.Extension;
import hudson.Functions;
import hudson.AbortException;
import hudson.util.FormValidation;
import hudson.slaves.OfflineCause;

import com.vmware.vim25.mo.Folder;
import com.vmware.vim25.mo.InventoryNavigator;
import com.vmware.vim25.mo.ServiceInstance;
import com.vmware.vim25.mo.VirtualMachine;

import com.vmware.vim25.mo.VirtualMachineSnapshot;
import hudson.Util;
import hudson.model.Result;
import hudson.model.Run;
import hudson.slaves.OfflineCause;
import java.util.List;
import java.util.ArrayList;
import java.io.IOException;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

/**
 *
 * @author Admin
 */
public class vSphereCloudSlave extends Slave {

    private final String vsDescription;
    private final String vmName;
    private final String snapName;
    private final Boolean waitForVMTools;
    private final String launchDelay;
    private final String idleOption;
    private transient Integer LimitedTestRunCount = 0; // If limited test runs enabled, the number of tests to limit the slave too.
    private transient Integer NumberOfLimitedTestRuns = 0;

    @DataBoundConstructor
    public vSphereCloudSlave(String name, String nodeDescription,
            String remoteFS, String numExecutors, Mode mode,
            String labelString, ComputerLauncher delegateLauncher,
            RetentionStrategy retentionStrategy,
            List<? extends NodeProperty<?>> nodeProperties,
            String vsDescription, String vmName,
            boolean launchSupportForced, boolean waitForVMTools,
            String snapName, String launchDelay, String idleOption,
            String LimitedTestRunCount)
            throws FormException, IOException {
        super(name, nodeDescription, remoteFS, numExecutors, mode, labelString,
                new vSphereCloudLauncher(delegateLauncher, vsDescription, vmName,
                    launchSupportForced, waitForVMTools, snapName, launchDelay, 
                    idleOption, LimitedTestRunCount),
                retentionStrategy, nodeProperties);
        this.vsDescription = vsDescription;
        this.vmName = vmName;
        this.snapName = snapName;
        this.waitForVMTools = waitForVMTools;
        this.launchDelay = launchDelay;
        this.idleOption = idleOption;   
        this.LimitedTestRunCount = Util.tryParseNumber(LimitedTestRunCount, 0).intValue();        
        this.NumberOfLimitedTestRuns = 0;
    }

    public String getVmName() {
        return vmName;
    }

    public String getVsDescription() {
        return vsDescription;
    }

    public String getSnapName() {
        return snapName;
    }

    public Boolean getWaitForVMTools() {
        return waitForVMTools;
    }

    public String getLaunchDelay() {
        return launchDelay;
    }

    public String getIdleOption() {
        return idleOption;
    }
    
    public Integer getLimitedTestRunCount() {
        return LimitedTestRunCount;
    }

    public boolean isLaunchSupportForced() {
        return ((vSphereCloudLauncher) getLauncher()).getOverrideLaunchSupported() == Boolean.TRUE;
    }

    public void setLaunchSupportForced(boolean slaveLaunchesOnBootup) {
        ((vSphereCloudLauncher) getLauncher()).setOverrideLaunchSupported(slaveLaunchesOnBootup ? Boolean.TRUE : null);
    }
    
    public boolean StartLimitedTestRun(Run r, TaskListener listener) {
        boolean ret = false;
        boolean DoUpdates = false;
        if (LimitedTestRunCount > 0) {
            DoUpdates = true;
            if (NumberOfLimitedTestRuns < LimitedTestRunCount) {
                ret = true;
            }
        }
        else
            ret = true;
        
        if (DoUpdates) {
            if (ret) {
                NumberOfLimitedTestRuns++;
                listener.getLogger().printf("vSphere Cloud: Starting limited count build: %d", NumberOfLimitedTestRuns);
            }
            else {
                listener.getLogger().printf("vSphere Cloud: Terminating build due to limited build count: %d", LimitedTestRunCount);
                r.getExecutor().interrupt(Result.ABORTED);
            }
        }
        
        return ret;
    }

    public boolean EndLimitedTestRun(Run r) {
        boolean ret = true;
        if (LimitedTestRunCount > 0) {
            if (NumberOfLimitedTestRuns >= LimitedTestRunCount) {
                ret = false;
                NumberOfLimitedTestRuns = 0;       
                r.getExecutor().getOwner().disconnect();
            }            
        }
        else
            ret = true;
        return ret;
    }
    
    /**
     * For UI.
     *
     * @return original launcher
     */
    public ComputerLauncher getDelegateLauncher() {
        return ((vSphereCloudLauncher) getLauncher()).getDelegate();
    }

    @Extension
    public static class vSphereCloudComputerListener extends ComputerListener {

        @Override
        public void preLaunch(Computer c, TaskListener taskListener) throws IOException, InterruptedException {
            /* We may be called on any slave type so check that we should
             * be in here. */
            if (!(c.getNode() instanceof vSphereCloudSlave)) {
                return;
            }

            vSphereCloudLauncher vsL = (vSphereCloudLauncher) ((SlaveComputer) c).getLauncher();
            vSphereCloud vsC = vsL.findOurVsInstance();
            if (!vsC.markVMOnline(c.getDisplayName(), vsL.getVmName()))
                throw new AbortException("The vSphere cloud will not allow this slave to start at this time.");
        }               
    }

    @Extension
    public static final class DescriptorImpl extends SlaveDescriptor {

        public DescriptorImpl() {
            load();
        }

        public String getDisplayName() {
            return "Slave virtual computer running under vSphere Cloud";
        }

        @Override
        public boolean isInstantiable() {
            return true;
        }

        public List<vSphereCloud> getvSphereClouds() {
            List<vSphereCloud> result = new ArrayList<vSphereCloud>();
            for (Cloud cloud : Hudson.getInstance().clouds) {
                if (cloud instanceof vSphereCloud) {
                    result.add((vSphereCloud) cloud);
                }
            }
            return result;
        }

        public vSphereCloud getSpecificvSphereCloud(String vsDescription)
                throws Exception {
            for (vSphereCloud vs : getvSphereClouds()) {
                if (vs.getVsDescription().equals(vsDescription)) {
                    return vs;
                }
            }
            throw new Exception("The vSphere Cloud doesn't exist");
        }

        public List<Descriptor<ComputerLauncher>> getComputerLauncherDescriptors() {
            List<Descriptor<ComputerLauncher>> result = new ArrayList<Descriptor<ComputerLauncher>>();
            for (Descriptor<ComputerLauncher> launcher : Functions.getComputerLauncherDescriptors()) {
                if (!vSphereCloudLauncher.class.isAssignableFrom(launcher.clazz)) {
                    result.add(launcher);
                }
            }
            return result;
        }

        public List<String> getIdleOptions() {
            List<String> options = new ArrayList<String>();
            options.add("Shutdown");
            options.add("Shutdown and Revert");
            options.add("Suspend");
            options.add("Reset");
            options.add("Nothing");                    
            return options;
        }

        public FormValidation doCheckLaunchDelay(@QueryParameter String value) {
            return FormValidation.validatePositiveInteger(value);
        }

        public FormValidation doTestConnection(@QueryParameter String vsDescription,
                @QueryParameter String vmName,
                @QueryParameter String snapName) {
            try {
                vSphereCloud vsC = getSpecificvSphereCloud(vsDescription);
                ServiceInstance si = vsC.getSI();

                Folder rootFolder = si.getRootFolder();                
                VirtualMachine vm = (VirtualMachine) new InventoryNavigator(
                        rootFolder).searchManagedEntity("VirtualMachine", vmName);                
                if (vm == null) {
                    return FormValidation.error("Virtual Machine was not found");
                }
                
                if (!snapName.isEmpty()) {
                    VirtualMachineSnapshot snap = vsC.getSnapshotInTree(vm, snapName);
                    if (snap == null)
                        return FormValidation.error("Virtual Machine snapshot was not found");
                }

                return FormValidation.ok("Virtual Machine found successfully");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }
}
