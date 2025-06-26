package com.couchbase.admin.connections.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public class ClusterMetrics {
    
    @JsonProperty("nodes")
    private List<NodeMetrics> nodes;
    
    @JsonProperty("buckets")
    private List<BucketMetrics> buckets;
    
    @JsonProperty("clusterName")
    private String clusterName;
    
    @JsonProperty("retrievedAt")
    private long retrievedAt;
    
    @JsonProperty("storageTotals")
    private StorageTotals storageTotals;
    
    @JsonProperty("alerts")
    private List<ClusterAlert> alerts;
    
    @JsonProperty("serviceQuotas")
    private ServiceQuotas serviceQuotas;
    
    public ClusterMetrics() {
        this.retrievedAt = System.currentTimeMillis();
    }
    
    public ClusterMetrics(List<NodeMetrics> nodes, List<BucketMetrics> buckets, String clusterName) {
        this.nodes = nodes;
        this.buckets = buckets;
        this.clusterName = clusterName;
        this.retrievedAt = System.currentTimeMillis();
    }
    
    public ClusterMetrics(List<NodeMetrics> nodes, List<BucketMetrics> buckets, String clusterName, 
                         StorageTotals storageTotals, List<ClusterAlert> alerts) {
        this.nodes = nodes;
        this.buckets = buckets;
        this.clusterName = clusterName;
        this.storageTotals = storageTotals;
        this.alerts = alerts;
        this.retrievedAt = System.currentTimeMillis();
    }
    
    public ClusterMetrics(List<NodeMetrics> nodes, List<BucketMetrics> buckets, String clusterName, 
                         StorageTotals storageTotals, List<ClusterAlert> alerts, ServiceQuotas serviceQuotas) {
        this.nodes = nodes;
        this.buckets = buckets;
        this.clusterName = clusterName;
        this.storageTotals = storageTotals;
        this.alerts = alerts;
        this.serviceQuotas = serviceQuotas;
        this.retrievedAt = System.currentTimeMillis();
    }
    
    // Getters and Setters
    public List<NodeMetrics> getNodes() {
        return nodes;
    }
    
    public void setNodes(List<NodeMetrics> nodes) {
        this.nodes = nodes;
    }
    
    public List<BucketMetrics> getBuckets() {
        return buckets;
    }
    
    public void setBuckets(List<BucketMetrics> buckets) {
        this.buckets = buckets;
    }
    
    public String getClusterName() {
        return clusterName;
    }
    
    public void setClusterName(String clusterName) {
        this.clusterName = clusterName;
    }
    
    public long getRetrievedAt() {
        return retrievedAt;
    }
    
    public void setRetrievedAt(long retrievedAt) {
        this.retrievedAt = retrievedAt;
    }
    
    public StorageTotals getStorageTotals() {
        return storageTotals;
    }
    
    public void setStorageTotals(StorageTotals storageTotals) {
        this.storageTotals = storageTotals;
    }
    
    public List<ClusterAlert> getAlerts() {
        return alerts;
    }
    
    public void setAlerts(List<ClusterAlert> alerts) {
        this.alerts = alerts;
    }
    
    public ServiceQuotas getServiceQuotas() {
        return serviceQuotas;
    }
    
    public void setServiceQuotas(ServiceQuotas serviceQuotas) {
        this.serviceQuotas = serviceQuotas;
    }
    
    // Inner class for Node Metrics
    public static class NodeMetrics {
        @JsonProperty("hostname")
        private String hostname;
        
        @JsonProperty("status")
        private String status;
        
        @JsonProperty("cpu")
        private int cpu;
        
        @JsonProperty("ram")
        private double ram; // in GB
        
        @JsonProperty("cpuUtilizationRate")
        private double cpuUtilizationRate;
        
        @JsonProperty("ramUtilizationRate")
        private double ramUtilizationRate;
        
        @JsonProperty("services")
        private List<String> services;
        
        @JsonProperty("version")
        private String version;
        
        @JsonProperty("diskUtilizationRate")
        private double diskUtilizationRate;

        public NodeMetrics() {}
        
        public NodeMetrics(String hostname, String status, int cpu, double ram, 
                      double cpuUtilizationRate, double ramUtilizationRate, 
                      List<String> services, String version, double diskUtilizationRate) {
            this.hostname = hostname;
            this.status = status;
            this.cpu = cpu;
            this.ram = ram;
            this.cpuUtilizationRate = cpuUtilizationRate;
            this.ramUtilizationRate = ramUtilizationRate;
            this.services = services;
            this.version = version;
            this.diskUtilizationRate = diskUtilizationRate;
        }
        
        // Getters and Setters
        public String getHostname() {
            return hostname;
        }
        
        public void setHostname(String hostname) {
            this.hostname = hostname;
        }
        
        public String getStatus() {
            return status;
        }
        
        public void setStatus(String status) {
            this.status = status;
        }
        
        public int getCpu() {
            return cpu;
        }
        
        public void setCpu(int cpu) {
            this.cpu = cpu;
        }
        
        public double getRam() {
            return ram;
        }
        
        public void setRam(double ram) {
            this.ram = ram;
        }
        
        public double getCpuUtilizationRate() {
            return cpuUtilizationRate;
        }
        
        public void setCpuUtilizationRate(double cpuUtilizationRate) {
            this.cpuUtilizationRate = cpuUtilizationRate;
        }
        
        public double getRamUtilizationRate() {
            return ramUtilizationRate;
        }
        
        public void setRamUtilizationRate(double ramUtilizationRate) {
            this.ramUtilizationRate = ramUtilizationRate;
        }
        
        public List<String> getServices() {
            return services;
        }
        
        public void setServices(List<String> services) {
            this.services = services;
        }
        
        public String getVersion() { 
            return version; 
        }
        
        public void setVersion(String version) { 
            this.version = version; 
        }
        
        public double getDiskUtilizationRate() { 
            return diskUtilizationRate; 
        }
        
        public void setDiskUtilizationRate(double diskUtilizationRate) { 
            this.diskUtilizationRate = diskUtilizationRate; 
        }
    }
    
    // Inner class for Bucket Metrics
    public static class BucketMetrics {
        @JsonProperty("name")
        private String name;
        
        @JsonProperty("ramQuota")
        private int ramQuota; // in MB
        
        @JsonProperty("ramUsed")
        private int ramUsed; // in MB
        
        @JsonProperty("itemCount")
        private long itemCount;
        
        @JsonProperty("diskUsed")
        private long diskUsed; // in bytes
        
        // Enhanced metrics from basicStats
        @JsonProperty("opsPerSec")
        private double opsPerSec;
        
        @JsonProperty("diskFetches")
        private long diskFetches;
        
        @JsonProperty("residentRatio")
        private double residentRatio; // Calculated: (itemCount - vbActiveNumNonResident)/itemCount * 100
        
        @JsonProperty("quotaPercentUsed")
        private double quotaPercentUsed;
        
        @JsonProperty("dataUsed")
        private long dataUsed; // in bytes
        
        @JsonProperty("vbActiveNumNonResident")
        private long vbActiveNumNonResident;
        
        // Storage totals for this bucket (if available)
        @JsonProperty("bucketStorageTotals")
        private StorageTotals bucketStorageTotals;
        
        // FHIR bucket status
        @JsonProperty("isFhirBucket")
        private Boolean isFhirBucket;
        
        // Bucket status (Ready or Building)
        @JsonProperty("status")
        private String status;
        
        public BucketMetrics() {}
        
        public BucketMetrics(String name, int ramQuota, int ramUsed, long itemCount, long diskUsed) {
            this.name = name;
            this.ramQuota = ramQuota;
            this.ramUsed = ramUsed;
            this.itemCount = itemCount;
            this.diskUsed = diskUsed;
        }
        
        public BucketMetrics(String name, int ramQuota, int ramUsed, long itemCount, long diskUsed,
                            double opsPerSec, long diskFetches, double residentRatio, double quotaPercentUsed,
                            long dataUsed, long vbActiveNumNonResident, StorageTotals bucketStorageTotals) {
            this.name = name;
            this.ramQuota = ramQuota;
            this.ramUsed = ramUsed;
            this.itemCount = itemCount;
            this.diskUsed = diskUsed;
            this.opsPerSec = opsPerSec;
            this.diskFetches = diskFetches;
            this.residentRatio = residentRatio;
            this.quotaPercentUsed = quotaPercentUsed;
            this.dataUsed = dataUsed;
            this.vbActiveNumNonResident = vbActiveNumNonResident;
            this.bucketStorageTotals = bucketStorageTotals;
        }
        
        public BucketMetrics(String name, int ramQuota, int ramUsed, long itemCount, long diskUsed,
                            double opsPerSec, long diskFetches, double residentRatio, double quotaPercentUsed,
                            long dataUsed, long vbActiveNumNonResident, StorageTotals bucketStorageTotals,
                            Boolean isFhirBucket) {
            this.name = name;
            this.ramQuota = ramQuota;
            this.ramUsed = ramUsed;
            this.itemCount = itemCount;
            this.diskUsed = diskUsed;
            this.opsPerSec = opsPerSec;
            this.diskFetches = diskFetches;
            this.residentRatio = residentRatio;
            this.quotaPercentUsed = quotaPercentUsed;
            this.dataUsed = dataUsed;
            this.vbActiveNumNonResident = vbActiveNumNonResident;
            this.bucketStorageTotals = bucketStorageTotals;
            this.isFhirBucket = isFhirBucket;
        }
        
        public BucketMetrics(String name, int ramQuota, int ramUsed, long itemCount, long diskUsed,
                            double opsPerSec, long diskFetches, double residentRatio, double quotaPercentUsed,
                            long dataUsed, long vbActiveNumNonResident, StorageTotals bucketStorageTotals,
                            Boolean isFhirBucket, String status) {
            this.name = name;
            this.ramQuota = ramQuota;
            this.ramUsed = ramUsed;
            this.itemCount = itemCount;
            this.diskUsed = diskUsed;
            this.opsPerSec = opsPerSec;
            this.diskFetches = diskFetches;
            this.residentRatio = residentRatio;
            this.quotaPercentUsed = quotaPercentUsed;
            this.dataUsed = dataUsed;
            this.vbActiveNumNonResident = vbActiveNumNonResident;
            this.bucketStorageTotals = bucketStorageTotals;
            this.isFhirBucket = isFhirBucket;
            this.status = status;
        }
        
        // Getters and Setters
        public String getName() {
            return name;
        }
        
        public void setName(String name) {
            this.name = name;
        }
        
        public int getRamQuota() {
            return ramQuota;
        }
        
        public void setRamQuota(int ramQuota) {
            this.ramQuota = ramQuota;
        }
        
        public int getRamUsed() {
            return ramUsed;
        }
        
        public void setRamUsed(int ramUsed) {
            this.ramUsed = ramUsed;
        }
        
        public long getItemCount() {
            return itemCount;
        }
        
        public void setItemCount(long itemCount) {
            this.itemCount = itemCount;
        }
        
        public long getDiskUsed() {
            return diskUsed;
        }
        
        public void setDiskUsed(long diskUsed) {
            this.diskUsed = diskUsed;
        }
        
        // Getters and setters for enhanced metrics
        public double getOpsPerSec() { return opsPerSec; }
        public void setOpsPerSec(double opsPerSec) { this.opsPerSec = opsPerSec; }
        
        public long getDiskFetches() { return diskFetches; }
        public void setDiskFetches(long diskFetches) { this.diskFetches = diskFetches; }
        
        public double getResidentRatio() { return residentRatio; }
        public void setResidentRatio(double residentRatio) { this.residentRatio = residentRatio; }
        
        public double getQuotaPercentUsed() { return quotaPercentUsed; }
        public void setQuotaPercentUsed(double quotaPercentUsed) { this.quotaPercentUsed = quotaPercentUsed; }
        
        public long getDataUsed() { return dataUsed; }
        public void setDataUsed(long dataUsed) { this.dataUsed = dataUsed; }
        
        public long getVbActiveNumNonResident() { return vbActiveNumNonResident; }
        public void setVbActiveNumNonResident(long vbActiveNumNonResident) { this.vbActiveNumNonResident = vbActiveNumNonResident; }
        
        public StorageTotals getBucketStorageTotals() { return bucketStorageTotals; }
        public void setBucketStorageTotals(StorageTotals bucketStorageTotals) { this.bucketStorageTotals = bucketStorageTotals; }
        
        public Boolean getIsFhirBucket() { return isFhirBucket; }
        public void setIsFhirBucket(Boolean isFhirBucket) { this.isFhirBucket = isFhirBucket; }
        
        public String getStatus() {
            return status;
        }
        
        public void setStatus(String status) {
            this.status = status;
        }
    }
    
    // Inner class for Storage Totals
    public static class StorageTotals {
        @JsonProperty("ram")
        private RamInfo ram;
        
        @JsonProperty("hdd")
        private HddInfo hdd;
        
        public StorageTotals() {}
        
        public StorageTotals(RamInfo ram, HddInfo hdd) {
            this.ram = ram;
            this.hdd = hdd;
        }
        
        public RamInfo getRam() {
            return ram;
        }
        
        public void setRam(RamInfo ram) {
            this.ram = ram;
        }
        
        public HddInfo getHdd() {
            return hdd;
        }
        
        public void setHdd(HddInfo hdd) {
            this.hdd = hdd;
        }
        
        public static class RamInfo {
            @JsonProperty("total")
            private long total;
            
            @JsonProperty("quotaTotal")
            private long quotaTotal;
            
            @JsonProperty("quotaUsed")
            private long quotaUsed;
            
            @JsonProperty("used")
            private long used;
            
            @JsonProperty("usedByData")
            private long usedByData;
            
            @JsonProperty("quotaUsedPerNode")
            private long quotaUsedPerNode;
            
            @JsonProperty("quotaTotalPerNode")
            private long quotaTotalPerNode;
            
            public RamInfo() {}
            
            // Getters and setters
            public long getTotal() { return total; }
            public void setTotal(long total) { this.total = total; }
            public long getQuotaTotal() { return quotaTotal; }
            public void setQuotaTotal(long quotaTotal) { this.quotaTotal = quotaTotal; }
            public long getQuotaUsed() { return quotaUsed; }
            public void setQuotaUsed(long quotaUsed) { this.quotaUsed = quotaUsed; }
            public long getUsed() { return used; }
            public void setUsed(long used) { this.used = used; }
            public long getUsedByData() { return usedByData; }
            public void setUsedByData(long usedByData) { this.usedByData = usedByData; }
            public long getQuotaUsedPerNode() { return quotaUsedPerNode; }
            public void setQuotaUsedPerNode(long quotaUsedPerNode) { this.quotaUsedPerNode = quotaUsedPerNode; }
            public long getQuotaTotalPerNode() { return quotaTotalPerNode; }
            public void setQuotaTotalPerNode(long quotaTotalPerNode) { this.quotaTotalPerNode = quotaTotalPerNode; }
        }
        
        public static class HddInfo {
            @JsonProperty("total")
            private long total;
            
            @JsonProperty("quotaTotal")
            private long quotaTotal;
            
            @JsonProperty("used")
            private long used;
            
            @JsonProperty("usedByData")
            private long usedByData;
            
            @JsonProperty("free")
            private long free;
            
            public HddInfo() {}
            
            // Getters and setters
            public long getTotal() { return total; }
            public void setTotal(long total) { this.total = total; }
            public long getQuotaTotal() { return quotaTotal; }
            public void setQuotaTotal(long quotaTotal) { this.quotaTotal = quotaTotal; }
            public long getUsed() { return used; }
            public void setUsed(long used) { this.used = used; }
            public long getUsedByData() { return usedByData; }
            public void setUsedByData(long usedByData) { this.usedByData = usedByData; }
            public long getFree() { return free; }
            public void setFree(long free) { this.free = free; }
        }
    }
    
    // Inner class for Cluster Alerts
    public static class ClusterAlert {
        @JsonProperty("msg")
        private String msg;
        
        @JsonProperty("serverTime")
        private String serverTime;
        
        @JsonProperty("disableUIPopUp")
        private boolean disableUIPopUp;
        
        public ClusterAlert() {}
        
        public ClusterAlert(String msg, String serverTime, boolean disableUIPopUp) {
            this.msg = msg;
            this.serverTime = serverTime;
            this.disableUIPopUp = disableUIPopUp;
        }
        
        public String getMsg() {
            return msg;
        }
        
        public void setMsg(String msg) {
            this.msg = msg;
        }
        
        public String getServerTime() {
            return serverTime;
        }
        
        public void setServerTime(String serverTime) {
            this.serverTime = serverTime;
        }
        
        public boolean isDisableUIPopUp() {
            return disableUIPopUp;
        }
        
        public void setDisableUIPopUp(boolean disableUIPopUp) {
            this.disableUIPopUp = disableUIPopUp;
        }
    }
    
    // Inner class for Service Memory Quotas
    public static class ServiceQuotas {
        @JsonProperty("memoryQuota")
        private int memoryQuota;
        
        @JsonProperty("queryMemoryQuota")
        private int queryMemoryQuota;
        
        @JsonProperty("indexMemoryQuota")
        private int indexMemoryQuota;
        
        @JsonProperty("ftsMemoryQuota")
        private int ftsMemoryQuota;
        
        @JsonProperty("cbasMemoryQuota")
        private int cbasMemoryQuota;
        
        @JsonProperty("eventingMemoryQuota")
        private int eventingMemoryQuota;
        
        public ServiceQuotas() {}
        
        public ServiceQuotas(int memoryQuota, int queryMemoryQuota, int indexMemoryQuota, 
                           int ftsMemoryQuota, int cbasMemoryQuota, int eventingMemoryQuota) {
            this.memoryQuota = memoryQuota;
            this.queryMemoryQuota = queryMemoryQuota;
            this.indexMemoryQuota = indexMemoryQuota;
            this.ftsMemoryQuota = ftsMemoryQuota;
            this.cbasMemoryQuota = cbasMemoryQuota;
            this.eventingMemoryQuota = eventingMemoryQuota;
        }
        
        // Getters and setters
        public int getMemoryQuota() { return memoryQuota; }
        public void setMemoryQuota(int memoryQuota) { this.memoryQuota = memoryQuota; }
        public int getQueryMemoryQuota() { return queryMemoryQuota; }
        public void setQueryMemoryQuota(int queryMemoryQuota) { this.queryMemoryQuota = queryMemoryQuota; }
        public int getIndexMemoryQuota() { return indexMemoryQuota; }
        public void setIndexMemoryQuota(int indexMemoryQuota) { this.indexMemoryQuota = indexMemoryQuota; }
        public int getFtsMemoryQuota() { return ftsMemoryQuota; }
        public void setFtsMemoryQuota(int ftsMemoryQuota) { this.ftsMemoryQuota = ftsMemoryQuota; }
        public int getCbasMemoryQuota() { return cbasMemoryQuota; }
        public void setCbasMemoryQuota(int cbasMemoryQuota) { this.cbasMemoryQuota = cbasMemoryQuota; }
        public int getEventingMemoryQuota() { return eventingMemoryQuota; }
        public void setEventingMemoryQuota(int eventingMemoryQuota) { this.eventingMemoryQuota = eventingMemoryQuota; }
    }
} 