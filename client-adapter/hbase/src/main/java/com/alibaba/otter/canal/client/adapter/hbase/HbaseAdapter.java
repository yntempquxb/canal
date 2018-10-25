package com.alibaba.otter.canal.client.adapter.hbase;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.*;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.filter.FirstKeyOnlyFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.otter.canal.client.adapter.OuterAdapter;
import com.alibaba.otter.canal.client.adapter.hbase.config.MappingConfig;
import com.alibaba.otter.canal.client.adapter.hbase.config.MappingConfigLoader;
import com.alibaba.otter.canal.client.adapter.hbase.service.HbaseEtlService;
import com.alibaba.otter.canal.client.adapter.hbase.service.HbaseSyncService;
import com.alibaba.otter.canal.client.adapter.hbase.support.HbaseTemplate;
import com.alibaba.otter.canal.client.adapter.support.*;

/**
 * HBase外部适配器
 *
 * @author machengyuan 2018-8-21 下午8:45:38
 * @version 1.0.0
 */
@SPI("hbase")
public class HbaseAdapter implements OuterAdapter {

    private static Logger                              logger             = LoggerFactory.getLogger(HbaseAdapter.class);

    private static volatile Map<String, MappingConfig> hbaseMapping       = null;                                       // 文件名对应配置
    private static volatile Map<String, MappingConfig> mappingConfigCache = null;                                       // 库名-表名对应配置

    private Connection                                 conn;
    private HbaseSyncService                           hbaseSyncService;
    private HbaseTemplate                              hbaseTemplate;

    @Override
    public void init(OuterAdapterConfig configuration) {
        try {
            if (mappingConfigCache == null) {
                synchronized (MappingConfig.class) {
                    if (mappingConfigCache == null) {
                        hbaseMapping = MappingConfigLoader.load();
                        mappingConfigCache = new HashMap<>();
                        for (MappingConfig mappingConfig : hbaseMapping.values()) {
                            mappingConfigCache.put(mappingConfig.getHbaseOrm().getDatabase() + "-"
                                                   + mappingConfig.getHbaseOrm().getTable(),
                                mappingConfig);
                        }
                    }
                }
            }

            Map<String, String> propertites = configuration.getProperties();

            Configuration hbaseConfig = HBaseConfiguration.create();
            propertites.forEach(hbaseConfig::set);
            conn = ConnectionFactory.createConnection(hbaseConfig);
            hbaseTemplate = new HbaseTemplate(conn);
            hbaseSyncService = new HbaseSyncService(hbaseTemplate);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void sync(Dml dml) {
        if (dml == null) {
            return;
        }
        String database = dml.getDatabase();
        String table = dml.getTable();
        MappingConfig config = mappingConfigCache.get(database + "-" + table);
        hbaseSyncService.sync(config, dml);
    }

    @Override
    public EtlResult etl(String task, List<String> params) {
        MappingConfig config = hbaseMapping.get(task);
        DataSource dataSource = DatasourceConfig.DATA_SOURCES.get(config.getDataSourceKey());
        if (dataSource != null) {
            return HbaseEtlService.importData(dataSource, hbaseTemplate, config, params);
        } else {
            EtlResult etlResult = new EtlResult();
            etlResult.setSucceeded(false);
            etlResult.setErrorMessage("DataSource not found");
            return etlResult;
        }
    }

    @Override
    public Map<String, Object> count(String task) {
        MappingConfig config = hbaseMapping.get(task);
        String hbaseTable = config.getHbaseOrm().getHbaseTable();
        long rowCount = 0L;
        try {
            HTable table = (HTable) conn.getTable(TableName.valueOf(hbaseTable));
            Scan scan = new Scan();
            scan.setFilter(new FirstKeyOnlyFilter());
            ResultScanner resultScanner = table.getScanner(scan);
            for (Result result : resultScanner) {
                rowCount += result.size();
            }
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("hbaseTable", hbaseTable);
        res.put("count", rowCount);
        return res;
    }

    @Override
    public void destroy() {
        if (conn != null) {
            try {
                conn.close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
