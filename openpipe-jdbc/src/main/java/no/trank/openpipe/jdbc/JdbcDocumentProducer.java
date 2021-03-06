/*
 * Copyright 2007  T-Rank AS
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package no.trank.openpipe.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.support.JdbcUtils;
import org.springframework.jdbc.support.SQLExceptionTranslator;

import no.trank.openpipe.api.document.Document;
import no.trank.openpipe.api.document.DocumentProducer;
import no.trank.openpipe.util.Iterators;

/**
 * @version $Revision$
 */
public class JdbcDocumentProducer implements DocumentProducer {
   private static final Logger log = LoggerFactory.getLogger(JdbcDocumentProducer.class);
   private JdbcStats jdbcStats;
   private JdbcTemplate jdbcTemplate;
   private DocumentMapper documentMapper;
   private List<? extends OperationPart> operationParts;
   private SqlIterator sqlIterator;

   @Override
   public void init() {
      if (documentMapper == null) {
         log.debug("No documentMapper provided, using MetaDataDocumentMapper");
         documentMapper = new MetaDataDocumentMapper();
      }
      
      if (jdbcStats == null) {
         log.debug("No jdbcStats provided, using NoopJdbcStats");
         jdbcStats = new NoopJdbcStats();
      }
      
      if (operationParts == null || operationParts.isEmpty()) {
         log.warn("No operationParts provided!");
      }
   }

   @Override
   public void close() {
      if (sqlIterator != null) {
         sqlIterator.runPostSqls();
      }
   }

   @Override
   public void fail() {
      if (sqlIterator != null) {
         sqlIterator.runFailSqls();
      }
   }

   @Override
   public Iterator<Document> iterator() {
      if (sqlIterator == null) {
         // a wrapper iterator. handles pre and post sql.
         sqlIterator = new SqlIterator(jdbcTemplate, notEmpty(operationParts), jdbcStats, documentMapper);
         return sqlIterator;
      } else {
         throw new IllegalStateException("Iterator can only be fetched once");
      }
   }

   private static List<OperationPart> notEmpty(List<? extends OperationPart> parts) {
      if (parts == null || parts.isEmpty()) {
         return Collections.emptyList();
      }
      final List<OperationPart> list = new ArrayList<OperationPart>(parts.size());
      for (OperationPart part : parts) {
         if (!part.isEmpty()) {
            list.add(part);
         }
      }
      return list;
   }

   public List<? extends OperationPart> getOperationParts() {
      return operationParts;
   }

   public void setOperationParts(List<? extends OperationPart> operationParts) {
      this.operationParts = operationParts;
   }

   public JdbcStats getJdbcStats() {
      return jdbcStats;
   }

   public void setJdbcStats(JdbcStats jdbcStats) {
      this.jdbcStats = jdbcStats;
   }

   public JdbcTemplate getJdbcTemplate() {
      return jdbcTemplate;
   }

   public void setJdbcTemplate(JdbcTemplate jdbcTemplate) {
      this.jdbcTemplate = jdbcTemplate;
   }

   public DocumentMapper getDocumentMapper() {
      return documentMapper;
   }

   public void setDocumentMapper(DocumentMapper documentMapper) {
      this.documentMapper = documentMapper;
   }

   public static interface OperationPart {

      public boolean isEmpty();

      public String getOperation();

      public void doPreSql(JdbcTemplate jdbcTemplate) throws DataAccessException;

      public void doPostSql(JdbcTemplate jdbcTemplate) throws DataAccessException;

      public void doFailSql(JdbcTemplate jdbcTemplate) throws DataAccessException;
      
      public List<String> getSqls();

   }

   private static class SqlIterator implements Iterator<Document> {
      private final JdbcTemplate jdbcTemplate;
      private final List<OperationPart> parts;
      private final Iterator<OperationPart> partIt;
      private final JdbcStats stats;
      private final DocumentMapper mapper;
      private OperationPart part;
      private Iterator<Document> docIt = Iterators.emptyIterator();

      public SqlIterator(JdbcTemplate jdbcTemplate, List<OperationPart> parts, JdbcStats stats,
            DocumentMapper mapper) throws DataAccessException {
         this.jdbcTemplate = jdbcTemplate;
         this.parts = parts;
         this.stats = stats;
         this.mapper = mapper;
         partIt = parts.iterator();
         findPart();
      }

      private void findPart() throws DataAccessException {
         endPart();
         if (part == null && partIt.hasNext()) {
            part = partIt.next();
            stats.startPreSql();
            part.doPreSql(jdbcTemplate);
            stats.startIt();
            docIt = new DocIterator(part.getSqls().iterator(), jdbcTemplate, mapper);
         }
      }

      private void endPart() throws DataAccessException {
         if (!docIt.hasNext() && part != null) {
            part = null;
         }
      }

      public void runPostSqls() {
         for (OperationPart operationPart : parts) {
            try {
               stats.startPostSql();
               operationPart.doPostSql(jdbcTemplate);
               stats.stop();
            } catch (RuntimeException e) {
               log.error("Could not run post sql", e);
            }
         }
      }

      public void runFailSqls() {
         for (OperationPart operationPart : parts) {
            try {
               stats.startFailSql();
               operationPart.doFailSql(jdbcTemplate);
               stats.stop();
            } catch (RuntimeException e) {
               log.error("Could not run fail sql", e);
            }
         }
      }

      @Override
      public boolean hasNext() {
         findPart();
         return docIt.hasNext();
      }

      @Override
      public Document next() {
         if (!hasNext()) {
            throw new NoSuchElementException();
         }
         final Document doc = docIt.next();
         final String op = part.getOperation();
         stats.incr(op);
         doc.setOperation(op);
         return doc;
      }

      @Override
      public void remove() {
         throw new UnsupportedOperationException();
      }

   }

   private static class DocIterator implements Iterator<Document> {
      private final Iterator<String> sqlIt;
      private final Connection connection;
      private final SQLExceptionTranslator translator;
      private final int fetchSize;
      private final DataSource dataSource;
      private ResultSet resultSet;
      private PreparedStatement prepSt;
      private Document doc;
      private DocumentMapper mapper;
      private String sql;

      public DocIterator(Iterator<String> sqls, JdbcTemplate jdbcTemplate, DocumentMapper mapper) {
         sqlIt = sqls;
         this.mapper = mapper;
         dataSource = jdbcTemplate.getDataSource();
         connection = DataSourceUtils.getConnection(dataSource);
         translator = jdbcTemplate.getExceptionTranslator();
         fetchSize = jdbcTemplate.getFetchSize();
         findDoc();
      }

      private void findDoc() throws DataAccessException {
         while (doc == null && (resultSet != null || sqlIt.hasNext())) {
            findResults();
            try {
               if (resultSet != null) {
                  if (resultSet.next()) {
                     doc = mapper.mapRow(resultSet, -1);
                  } else if (sqlIt.hasNext()) {
                     closeCurrent();
                  } else {
                     close();
                  }
               }
            } catch (SQLException e) {
               close();
               throw translator.translate("DocIterator", sql, e);
            }
         }
      }

      private void findResults() throws DataAccessException {
         if (resultSet == null && sqlIt.hasNext()) {
            sql = sqlIt.next();
            try {
               prepSt = connection.prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
               if (fetchSize > 0) {
                  prepSt.setFetchSize(fetchSize);
               }
               log.debug("Executing query {}", sql);
               resultSet = prepSt.executeQuery();
               mapper.reset(resultSet.getMetaData());
            } catch (SQLException e) {
               close();
               throw translator.translate("DocIterator", sql, e);
            }
         }
      }

      private void close() {
         closeCurrent();
         DataSourceUtils.releaseConnection(connection, dataSource);
      }

      private void closeCurrent() {
         JdbcUtils.closeResultSet(resultSet);
         JdbcUtils.closeStatement(prepSt);
         resultSet = null;
         prepSt = null;
      }

      @Override
      public boolean hasNext() {
         findDoc();
         return doc != null;
      }

      @Override
      public Document next() {
         if (!hasNext()) {
            throw new NoSuchElementException();
         }
         try {
            return doc;
         } finally {
            doc = null;
         }
      }

      @Override
      public void remove() {
         throw new UnsupportedOperationException();
      }
   }
}