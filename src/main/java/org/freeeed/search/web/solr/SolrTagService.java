/*
 *
 * Copyright SHMsoft, Inc. 
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
*/
package org.freeeed.search.web.solr;

import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.log4j.Logger;
import org.freeeed.search.web.configuration.Configuration;
import org.freeeed.search.web.dao.cases.CaseDao;
import org.freeeed.search.web.model.Case;
import org.freeeed.search.web.model.solr.SolrDocument;
import org.freeeed.search.web.model.solr.SolrResult;
import org.freeeed.search.web.model.solr.Tag;
import org.freeeed.search.web.session.SessionContext;
import org.freeeed.search.web.session.SolrSessionObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Class SolrTag.
 *
 * @author ilazarov.
 */
public class SolrTagService {
    private static final Logger log = Logger.getLogger(SolrTagService.class);

    private Configuration configuration;
    private SolrSearchService searchService;
    private CaseDao caseDao;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public Result removeTagFromAllDocs(String tag) {
        String query = "tags-search-field:" + tag;
        try {
            lock.writeLock().lock();
            while (true) {
                List<SolrDocument> docs = getDocumentTags(query, 0, configuration.getNumberOfRows());
                if (docs.isEmpty()) {
                    break;
                }
                updateTags(docs, tag, true);
                String updateJson = buildUpdateJson(docs);
                Result result = sendUpdateCommand(updateJson);
                if (result == Result.ERROR) {
                    return result;
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
        removeCaseTag(tag);
        return Result.SUCCESS;
    }

    public enum Result {
        SUCCESS,
        ERROR
    }

    /**
     * Tag a single document identified by its document id.
     *
     * @param documentId
     * @param tag
     * @return
     */
    public Result tagDocument(String documentId, String tag) {
        String query = "id:" + documentId;
        return process(query, tag, 0, 1, false);
    }

    /**
     * Tag all documents within the current search page.
     *
     * @param solrSession
     * @param tag
     * @return
     */
    public Result tagThisPageDocuments(SolrSessionObject solrSession, String tag) {
        String query = solrSession.buildSearchQuery();
        int from = (solrSession.getCurrentPage() - 1) * configuration.getNumberOfRows();

        return process(query, tag, from, configuration.getNumberOfRows(), false);
    }

    /**
     * Tag all documents within the current search.
     *
     * @param solrSession
     * @param tag
     * @return
     */
    public Result tagAllDocuments(SolrSessionObject solrSession, String tag) {
        String query = solrSession.buildSearchQuery();
        int rows = solrSession.getTotalDocuments();

        return process(query, tag, 0, rows, false);
    }

    /**
     * Remove a tag from the document identified by the given id.
     *
     * @param documentId
     * @param tag
     * @return
     */
    public Result removeTag(String documentId, String tag) {
        String query = "id:" + documentId;
        Result result = process(query, tag, 0, 1, true);
        if (result == Result.SUCCESS) {
            List<SolrDocument> docs = getDocumentTags("tags-search-field:" + tag, 0, 1);
            if (docs.isEmpty()) {
                removeCaseTag(tag);
            }
        }
        return result;
    }

    private Result process(String query, String tag, int from, int rows, boolean remove) {
        try {
            lock.writeLock().lock();
            List<SolrDocument> docs = getDocumentTags(query, from, rows);
            updateTags(docs, tag, remove);
            String updateJson = buildUpdateJson(docs);
            return sendUpdateCommand(updateJson);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private List<SolrDocument> getDocumentTags(String query, int from, int rows) {
        SolrResult solrResult = searchService.search(query, from, rows, null, false, "id,tags-search-field");
        List<SolrDocument> result = new ArrayList<SolrDocument>(solrResult.getTotalSize());
        result.addAll(solrResult.getDocuments().values());
        return result;
    }

    private void updateTags(List<SolrDocument> docTags, String tag, boolean remove) {
        for (SolrDocument docTag : docTags) {
            List<Tag> currentTags = docTag.getTags();
            if (remove) {
                removeTag(currentTags, tag);
            } else {
                if (!containsTag(currentTags, tag)) {
                    Tag tagObj = new Tag();
                    tagObj.setValue(tag);
                    tagObj.setName(tag);
                    currentTags.add(tagObj);
                }

                updateCaseTags(tag);
            }
        }
    }

    private void updateCaseTags(String tag) {
        SolrSessionObject solrSession = SessionContext.getSolrSession();
        if (solrSession != null && solrSession.getSelectedCase() != null) {
            Case c = solrSession.getSelectedCase();
            c.addTag(tag);
            caseDao.saveCase(c);
        }
    }

    private void removeCaseTag(String tag) {
        SolrSessionObject solrSession = SessionContext.getSolrSession();
        if (solrSession != null && solrSession.getSelectedCase() != null) {
            Case c = solrSession.getSelectedCase();
            c.removeTag(tag);
            caseDao.saveCase(c);
        }
    }

    private void removeTag(List<Tag> tags, String tag) {
        Iterator<Tag> i = tags.iterator();
        while (i.hasNext()) {
            Tag tagObj = i.next();
            if (tag.equalsIgnoreCase(tagObj.getValue())) {
                i.remove();
            }
        }
    }

    private boolean containsTag(List<Tag> tags, String tag) {
        for (Tag tagObj : tags) {
            if (tag.equalsIgnoreCase(tagObj.getValue())) {
                return true;
            }
        }

        return false;
    }

    private String buildUpdateJson(List<SolrDocument> docTags) {
        StringBuilder result = new StringBuilder();
        result.append("[");

        for (int i = 0; i < docTags.size(); i++) {
            SolrDocument docTag = docTags.get(i);

            result.append("{\"id\" : \"");
            result.append(docTag.getDocumentId());
            result.append("\", \"tags-search-field\" : {\"set\" : [");

            List<Tag> curTags = docTag.getTags();
            for (int j = 0; j < curTags.size(); j++) {
                Tag tagObj = curTags.get(j);
                result.append("\"");
                result.append(tagObj.getValue());
                result.append("\"");

                if (j < curTags.size() - 1) {
                    result.append(",");
                }
            }

            result.append("]}}");

            if (i < docTags.size() - 1) {
                result.append(",");
            }
        }

        result.append("]");

        return result.toString();
    }

    private Result sendUpdateCommand(String data) {
        SolrSessionObject solrSession = SessionContext.getSolrSession();
        if (solrSession == null || solrSession.getSelectedCase() == null) {
            return Result.ERROR;
        }

        String solrCore = solrSession.getSelectedCase().getSolrSourceCore();

        String url = configuration.getSolrEndpoint() + "/solr/"
                + solrCore + "/update?commit=true&wt=xml";

        log.debug("Will send request to: " + url + ", data: " + data);

        HttpClient httpClient = new DefaultHttpClient();

        try {
            HttpPost request = new HttpPost(url);
            StringEntity params = new StringEntity(data);
            params.setContentType("application/json");

            request.setEntity(params);

            HttpResponse response = httpClient.execute(request);
            response.getStatusLine().getStatusCode();
        } catch (Exception ex) {
            log.error("Problem tagging: " + ex);
            return Result.ERROR;
        }

        return Result.SUCCESS;
    }

    public void setConfiguration(Configuration configuration) {
        this.configuration = configuration;
    }

    public void setSearchService(SolrSearchService searchService) {
        this.searchService = searchService;
    }

    public void setCaseDao(CaseDao caseDao) {
        this.caseDao = caseDao;
    }
}
