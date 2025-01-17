/*
 * Lumeer: Modern Data Definition and Processing Platform
 *
 * Copyright (C) since 2017 Lumeer.io, s.r.o. and/or its affiliates.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package io.lumeer.core.task.executor.operation.stage;

import static java.util.stream.Collectors.*;

import io.lumeer.api.model.Collection;
import io.lumeer.api.model.CollectionPurposeType;
import io.lumeer.api.model.Document;
import io.lumeer.api.model.LinkInstance;
import io.lumeer.api.model.LinkType;
import io.lumeer.api.model.ResourceType;
import io.lumeer.core.adapter.LinkTypeAdapter;
import io.lumeer.core.facade.FunctionFacade;
import io.lumeer.core.facade.TaskProcessingFacade;
import io.lumeer.core.facade.detector.PurposeChangeProcessor;
import io.lumeer.core.task.AutoLinkBatchTask;
import io.lumeer.core.task.FunctionTask;
import io.lumeer.core.task.RuleTask;
import io.lumeer.core.task.TaskExecutor;
import io.lumeer.core.task.executor.ChangesTracker;
import io.lumeer.core.task.executor.operation.DocumentCreationOperation;
import io.lumeer.core.task.executor.operation.DocumentOperation;
import io.lumeer.core.task.executor.operation.DocumentRemovalOperation;
import io.lumeer.core.task.executor.operation.LinkCreationOperation;
import io.lumeer.core.task.executor.operation.LinkOperation;
import io.lumeer.core.task.executor.operation.NavigationOperation;
import io.lumeer.core.task.executor.operation.Operation;
import io.lumeer.core.task.executor.operation.OperationExecutor;
import io.lumeer.core.task.executor.operation.PrintAttributeOperation;
import io.lumeer.core.task.executor.operation.PrintTextOperation;
import io.lumeer.core.task.executor.operation.SendEmailOperation;
import io.lumeer.core.task.executor.operation.UserMessageOperation;
import io.lumeer.core.task.executor.request.GenericPrintRequest;
import io.lumeer.core.task.executor.request.NavigationRequest;
import io.lumeer.core.task.executor.request.SendEmailRequest;
import io.lumeer.core.task.executor.request.UserMessageRequest;
import io.lumeer.core.util.DocumentUtils;
import io.lumeer.core.util.LinkTypeUtils;
import io.lumeer.core.util.Utils;
import io.lumeer.engine.api.data.DataDocument;
import io.lumeer.engine.api.event.CreateDocument;
import io.lumeer.engine.api.event.CreateLinkInstance;
import io.lumeer.engine.api.event.UpdateDocument;
import io.lumeer.engine.api.event.UpdateLinkInstance;

import org.apache.commons.lang3.StringUtils;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public class SingleStage extends Stage {

   private final String automationName;

   private final LinkTypeAdapter linkTypeAdapter;

   public SingleStage(final OperationExecutor executor) {
      super(executor);

      if (task instanceof FunctionTask) {
         var functionTask = (FunctionTask) task;
         automationName = "=" + functionTask.getAttribute().getId();
      } else if (task instanceof RuleTask) {
         automationName = ((RuleTask) task).getRule().getName();
      } else if (task instanceof AutoLinkBatchTask) {
         automationName = ((AutoLinkBatchTask) task).getRule().getRule().getName();
      } else {
         automationName = null;
      }

      linkTypeAdapter = new LinkTypeAdapter(task.getDaoContextSnapshot().getLinkTypeDao(), task.getDaoContextSnapshot().getLinkInstanceDao());
   }

   @Override
   public ChangesTracker call() {
      return commitOperations();
   }

   private List<Document> createDocuments(final List<DocumentCreationOperation> operations) {
      if (operations.isEmpty()) {
         return List.of();
      }

      final List<Document> documents = operations.stream().map(DocumentCreationOperation::getEntity).peek(document -> {
         document.setCreatedBy(task.getInitiator().getId());
         document.setCreationDate(ZonedDateTime.now());
      }).collect(toList());

      return task.getDaoContextSnapshot().getDocumentDao().createDocuments(documents);
   }

   private List<Document> commitDocumentOperations(final List<DocumentOperation> operations,
         final List<Document> createdDocuments, final Map<String, Collection> collectionsMapForCreatedDocuments) {
      if (operations.isEmpty() && collectionsMapForCreatedDocuments.isEmpty()) {
         return List.of();
      }

      final FunctionFacade functionFacade = task.getFunctionFacade();
      final TaskProcessingFacade taskProcessingFacade = task.getTaskProcessingFacade(taskExecutor, functionFacade);
      final PurposeChangeProcessor purposeChangeProcessor = task.getPurposeChangeProcessor();

      final Map<String, List<Document>> updatedDocuments = new HashMap<>(); // Collection -> [Document]
      Map<String, Set<String>> documentIdsByCollection = operations.stream().map(Operation::getEntity)
                                                                   .collect(Collectors.groupingBy(Document::getCollectionId, mapping(Document::getId, toSet())));
      final Map<String, Collection> collectionsMap = task.getDaoContextSnapshot().getCollectionDao().getCollectionsByIds(documentIdsByCollection.keySet())
                                                         .stream().collect(Collectors.toMap(Collection::getId, coll -> coll));
      final Set<String> collectionsChanged = new HashSet<>();

      Map<String, Document> documentsByCorrelationId = createdDocuments.stream().collect(Collectors.toMap(doc -> doc.createIfAbsentMetaData().getString(Document.META_CORRELATION_ID), Function.identity()));

      // aggregate all operations to individual documents
      final Map<String, List<DocumentOperation>> changesByDocumentId = Utils.categorize(operations.stream(), change -> change.getEntity().getId());

      final Set<String> unprocessedCreatedDocuments = createdDocuments.stream().map(Document::getId).collect(toSet());

      createdDocuments.forEach(document -> {
         final Collection collection = collectionsMap.get(document.getCollectionId());
         final DataDocument newDataDecoded = constraintManager.encodeDataTypes(collection, document.getData());

         auditAdapter.registerCreate(collection.getId(), ResourceType.DOCUMENT, document.getId(), task.getInitiator(), automationName, null, newDataDecoded);
      });

      changesByDocumentId.forEach((id, changeList) -> {
         unprocessedCreatedDocuments.remove(id);
         final Document document = changeList.get(0).getEntity();
         final Document originalDocument =
               (task instanceof RuleTask) ? ((RuleTask) task).getOldDocument() :
                     ((task instanceof FunctionTask) ? ((FunctionTask) task).getOriginalDocumentOrDefault(id, changeList.get(0).getOriginalDocument()) :
                           changeList.get(0).getOriginalDocument());
         final Collection collection = collectionsMap.get(document.getCollectionId());
         final DataDocument aggregatedUpdate = new DataDocument();
         changeList.forEach(change -> aggregatedUpdate.put(change.getAttrId(), change.getValue()));
         final DataDocument newData = constraintManager.encodeDataTypes(collection, aggregatedUpdate);
         final DataDocument oldData = originalDocument != null ? new DataDocument(originalDocument.getData()) : new DataDocument();

         Set<String> attributesIdsToAdd = new HashSet<>(newData.keySet());
         attributesIdsToAdd.removeAll(oldData.keySet());

         if (attributesIdsToAdd.size() > 0) {
            collection.getAttributes().stream().filter(attr -> attributesIdsToAdd.contains(attr.getId())).forEach(attr -> {
               attr.setUsageCount(attr.getUsageCount() + 1);
               collection.setLastTimeUsed(ZonedDateTime.now());
               collectionsChanged.add(collection.getId());
            });
         }

         document.setUpdatedBy(task.getInitiator().getId());
         document.setUpdateDate(ZonedDateTime.now());

         final DataDocument beforePatch = task.getDaoContextSnapshot().getDataDao().getData(document.getCollectionId(), document.getId());

         DataDocument patchedData = task.getDaoContextSnapshot().getDataDao()
                                        .patchData(document.getCollectionId(), document.getId(), newData);

         Document updatedDocument = task.getDaoContextSnapshot().getDocumentDao()
                                        .updateDocument(document.getId(), document);

         updatedDocument.setData(patchedData);

         // notify delayed actions about data change
         if (collection.getPurposeType() == CollectionPurposeType.Tasks) {
            final Document original;
            if (originalDocument == null) { // when triggered by an action button, let's use the document from db
               original = new Document(document);
               original.setData(beforePatch);
            } else {
               original = originalDocument;
            }
            purposeChangeProcessor.processChanges(new UpdateDocument(updatedDocument, original), collection);
         }

         var oldDataDecoded = constraintManager.decodeDataTypes(collection, beforePatch);
         var patchedDataDecoded = constraintManager.decodeDataTypes(collection, patchedData);

         auditAdapter.registerDataChange(updatedDocument.getCollectionId(), ResourceType.DOCUMENT, updatedDocument.getId(),
               task.getInitiator(), automationName, null, beforePatch, oldDataDecoded, patchedData, patchedDataDecoded);

         // add patched data to new documents
         boolean created = false;
         if (StringUtils.isNotEmpty(document.createIfAbsentMetaData().getString(Document.META_CORRELATION_ID))) {
            final Document doc = documentsByCorrelationId.get(document.getMetaData().getString(Document.META_CORRELATION_ID));

            if (doc != null) {
               doc.setData(patchedData);
               created = true;
            }
         }

         if (task instanceof RuleTask) {
            if (created) {
               taskProcessingFacade.onCreateDocument(new CreateDocument(updatedDocument));
            } else {
               if (task.getRecursionDepth() == 0) {
                  // there are now 3 versions of the document:
                  // 1) the document before user triggered an update - original document (null when triggered by action button)
                  // 2) the document with the new user entered value - before patch
                  // 3) the document with the value computed by the rule based on the previous two - updated document
                  // this rule got executed because of change from 1 to 2
                  // for the recursive rules, we need to trigger rules for changes between 2 and 3
                  final UpdateDocument updateDocumentEvent;
                  final Document orig = new Document(document);
                  orig.setData(beforePatch);
                  updateDocumentEvent = new UpdateDocument(updatedDocument, orig);

                  taskProcessingFacade.onDocumentUpdate(updateDocumentEvent, ((RuleTask) task).getRule().getName());
               } else {
                  taskExecutor.submitTask(functionFacade.createTaskForUpdateDocument(collection, originalDocument, updatedDocument, aggregatedUpdate.keySet()));
               }
            }
         }

         patchedData = constraintManager.decodeDataTypes(collection, patchedData);
         updatedDocument.setData(patchedData);

         updatedDocuments.computeIfAbsent(document.getCollectionId(), key -> new ArrayList<>())
                         .add(updatedDocument);
      });

      unprocessedCreatedDocuments.forEach(id -> {
         createdDocuments.stream().filter(d -> d.getId().equals(id)).findFirst().ifPresent(document -> {
            taskProcessingFacade.onCreateDocument(new CreateDocument(document));
         });
      });

      changesTracker.addCollections(collectionsChanged.stream().map(collectionsMap::get).collect(toSet()));
      changesTracker.addUpdatedDocuments(updatedDocuments.values().stream().flatMap(java.util.Collection::stream).collect(toSet()));
      changesTracker.updateCollectionsMap(collectionsMapForCreatedDocuments);
      changesTracker.updateCollectionsMap(collectionsMap);

      collectionsChanged.forEach(collectionId -> task.getDaoContextSnapshot()
                                                     .getCollectionDao().updateCollection(collectionId, collectionsMap.get(collectionId), null, false));

      return updatedDocuments.values().stream().flatMap(java.util.Collection::stream).collect(toList());
   }

   private List<Document> removeDocuments(final List<DocumentRemovalOperation> operations) {
      if (!operations.isEmpty()) {
         final List<Document> documents = operations.stream().map(DocumentRemovalOperation::getEntity).collect(toList());

         final Map<String, Collection> allCollections = task.getDaoContextSnapshot().getCollectionDao().getCollectionsByIds(
               documents.stream().map(Document::getCollectionId).collect(toList())
         ).stream().collect(Collectors.toMap(Collection::getId, Function.identity()));
         final Map<String, LinkType> allLinkTypes = task.getDaoContextSnapshot().getLinkTypeDao().getAllLinkTypes().stream().collect(Collectors.toMap(LinkType::getId, Function.identity()));

         documents.forEach(document -> {
            // decrease documents count in collections map
            if (!changesTracker.getCollectionsMap().containsKey(document.getCollectionId())) { // not yet tracked
               final Collection collection = allCollections.get(document.getCollectionId());
               changesTracker.updateCollectionsMap(Map.of(collection.getId(), collection));
            }

            // decrease documents count in updated collections in operations tracker
            final Optional<Collection> changedCollection = changesTracker.getCollections().stream().filter(c -> c.getId().equals(document.getCollectionId())).findFirst();
            final Collection collection = allCollections.get(document.getCollectionId());
            if (changedCollection.isEmpty()) { // not yet tracked
               changesTracker.getCollections().add(collection);
            }

            final List<LinkInstance> removedLinks = task.getDaoContextSnapshot().getLinkInstanceDao().getLinkInstancesByDocumentIds(Set.of(document.getId()));
            final Set<String> removedFromLinkTypes = removedLinks.stream().map(LinkInstance::getLinkTypeId).collect(toSet());
            changesTracker.addRemovedLinkInstances(removedLinks);
            task.getDaoContextSnapshot().getLinkInstanceDao().deleteLinkInstancesByDocumentsIds(Set.of(document.getId()));
            removedLinks.forEach(link -> {
               var linkType = allLinkTypes.get(link.getLinkTypeId());
               var decodedDeletedData = constraintManager.decodeDataTypes(linkType, link.getData());
               auditAdapter.registerDelete(link.getLinkTypeId(), ResourceType.LINK, link.getId(), task.getInitiator(), automationName, null, decodedDeletedData);
            });

            removedFromLinkTypes.forEach(linkTypeId -> {
               // decrease link instances count in link types map
               if (changesTracker.getLinkTypesMap().containsKey(linkTypeId)) { // present in link types map
                  final LinkType linkType = changesTracker.getLinkTypesMap().get(linkTypeId);
                  linkTypeAdapter.mapLinkTypeComputedProperties(linkType);
               } else { // not yet in the map
                  final LinkType linkType = allLinkTypes.get(linkTypeId);
                  linkTypeAdapter.mapLinkTypeComputedProperties(linkType);
                  changesTracker.updateLinkTypesMap(Map.of(linkType.getId(), linkType));
               }

               // decrease link instances count in updated link types in operations tracker
               final Optional<LinkType> changedLinkType = changesTracker.getLinkTypes().stream().filter(l -> l.getId().equals(linkTypeId)).findFirst();
               final LinkType linkType = allLinkTypes.get(linkTypeId);
               if (changedLinkType.isPresent()) { // tracked in operations tracker
                  changedLinkType.get().setLinksCount(linkType.getLinksCount());
               } else { // not yet tracked
                  changesTracker.getLinkTypes().add(linkType);
               }
            });

            task.getDaoContextSnapshot().getDocumentDao().deleteDocument(document.getId(), document.getData());
            task.getDaoContextSnapshot().getDataDao().deleteData(document.getCollectionId(), document.getId());

            var decodedDeletedData = constraintManager.decodeDataTypes(collection, document.getData());
            auditAdapter.registerDelete(document.getCollectionId(), ResourceType.DOCUMENT, document.getId(), task.getInitiator(), automationName, null, decodedDeletedData);
         });

         return documents;
      }

      return List.of();
   }

   private List<LinkInstance> createLinks(final List<LinkCreationOperation> operations) {
      if (operations.isEmpty()) {
         return List.of();
      }

      final List<LinkInstance> linkInstances = operations.stream().map(LinkCreationOperation::getEntity).map(link -> {
         link.setCreatedBy(task.getInitiator().getId());
         link.setCreationDate(ZonedDateTime.now());

         return link;
      }).collect(toList());

      return task.getDaoContextSnapshot().getLinkInstanceDao().createLinkInstances(linkInstances, false);
   }

   private List<LinkInstance> commitLinkOperations(
         final TaskExecutor taskExecutor,
         final List<LinkOperation> changes,
         final List<LinkInstance> createdLinks,
         final Map<String, LinkType> linkTypeMapForCreatedLinks) {
      if (changes.isEmpty() && linkTypeMapForCreatedLinks.isEmpty()) {
         return List.of();
      }

      final FunctionFacade functionFacade = task.getFunctionFacade();
      final TaskProcessingFacade taskProcessingFacade = task.getTaskProcessingFacade(taskExecutor, functionFacade);

      final Map<String, List<LinkInstance>> updatedLinks = new HashMap<>(); // LinkType -> [LinkInstance]
      final Map<String, LinkType> linkTypesMap = task.getDaoContextSnapshot().getLinkTypeDao().getAllLinkTypes()
                                                     .stream().collect(Collectors.toMap(LinkType::getId, linkType -> linkType));
      Set<String> linkTypesChanged = new HashSet<>();

      Map<String, LinkInstance> linksByCorrelationId = createdLinks.stream().collect(Collectors.toMap(LinkInstance::getTemplateId, Function.identity()));

      // aggregate all changes to individual link instances
      final Map<String, List<LinkOperation>> changesByLinkTypeId = Utils.categorize(changes.stream(), change -> change.getEntity().getId());

      final Set<String> unprocessedCreatedLinks = createdLinks.stream().map(LinkInstance::getId).collect(toSet());

      changesByLinkTypeId.forEach((id, changeList) -> {
         unprocessedCreatedLinks.remove(id);
         final LinkInstance linkInstance = changeList.get(0).getEntity();
         final LinkInstance originalLinkInstance = (task instanceof RuleTask) ? ((RuleTask) task).getOldLinkInstance() :
               ((task instanceof FunctionTask) ? ((FunctionTask) task).getOriginalLinkInstanceOrDefault(id, changeList.get(0).getOriginalLinkInstance()) :
                     changeList.get(0).getOriginalLinkInstance());
         final LinkType linkType = linkTypesMap.get(linkInstance.getLinkTypeId());
         final DataDocument aggregatedUpdate = new DataDocument();
         changeList.forEach(change -> aggregatedUpdate.put(change.getAttrId(), change.getValue()));
         final DataDocument newData = constraintManager.encodeDataTypes(linkType, aggregatedUpdate);
         final DataDocument oldData = originalLinkInstance != null ? new DataDocument(originalLinkInstance.getData()) : new DataDocument();

         Set<String> attributesIdsToAdd = new HashSet<>(newData.keySet());
         attributesIdsToAdd.removeAll(oldData.keySet());

         if (attributesIdsToAdd.size() > 0) {
            linkType.getAttributes().stream().filter(attr -> attributesIdsToAdd.contains(attr.getId())).forEach(attr -> {
               attr.setUsageCount(attr.getUsageCount() + 1);
               linkTypesChanged.add(linkType.getId());
            });
         }

         linkInstance.setUpdatedBy(task.getInitiator().getId());
         linkInstance.setUpdateDate(ZonedDateTime.now());

         final DataDocument beforePatch = task.getDaoContextSnapshot().getLinkDataDao().getData(linkInstance.getLinkTypeId(), linkInstance.getId());

         // thanks to an auto-link rule, the link could have ceased to exist
         if (task.getDaoContextSnapshot().getLinkInstanceDao().getLinkInstances(Set.of(linkInstance.getId())).size() > 0){
            DataDocument patchedData = task.getDaoContextSnapshot().getLinkDataDao()
                 .patchData(linkInstance.getLinkTypeId(), linkInstance.getId(), newData);

            LinkInstance updatedLink = task.getDaoContextSnapshot().getLinkInstanceDao()
                    .updateLinkInstance(linkInstance.getId(), linkInstance);

            updatedLink.setData(patchedData);

            var oldDataDecoded = constraintManager.decodeDataTypes(linkType, beforePatch);
            var patchedDataDecoded = constraintManager.decodeDataTypes(linkType, patchedData);

            auditAdapter.registerDataChange(updatedLink.getLinkTypeId(), ResourceType.LINK, updatedLink.getId(),
                    task.getInitiator(), automationName, null, beforePatch, oldDataDecoded, patchedData, patchedDataDecoded);

            // add patched data to new links
            boolean created = false;
            if (StringUtils.isNotEmpty(linkInstance.getTemplateId())) {
               final LinkInstance link = linksByCorrelationId.get(linkInstance.getTemplateId());

               if (link != null) {
                  link.setData(patchedData);
                  created = true;
               }
            }

            if (task instanceof RuleTask) {
               if (created) {
                  taskProcessingFacade.onCreateLink(new CreateLinkInstance(updatedLink));
               } else {
                  if (task.getRecursionDepth() == 0) {
                     // there are now 3 versions of the document:
                     // 1) the document before user triggered an update - original document (null when triggered by action button)
                     // 2) the document with the new user entered value - before patch
                     // 3) the document with the value computed by the rule based on the previous two - updated document
                     // this rule got executed because of change from 1 to 2
                     // for the recursive rules, we need to trigger rules for changes between 2 and 3
                     final UpdateLinkInstance updateLinkInstanceEvent;
                     final LinkInstance orig = new LinkInstance(linkInstance);
                     orig.setData(beforePatch);
                     updateLinkInstanceEvent = new UpdateLinkInstance(updatedLink, orig);

                     taskProcessingFacade.onUpdateLink(updateLinkInstanceEvent, ((RuleTask) task).getRule().getName());
                  } else {
                     taskExecutor.submitTask(functionFacade.creatTaskForChangedLink(linkType, originalLinkInstance, updatedLink, aggregatedUpdate.keySet()));
                  }
               }
            }

            patchedData = constraintManager.decodeDataTypes(linkType, patchedData);
            updatedLink.setData(patchedData);

            updatedLinks.computeIfAbsent(linkInstance.getLinkTypeId(), key -> new ArrayList<>())
                    .add(updatedLink);
         }
      });

      unprocessedCreatedLinks.forEach(id -> {
         createdLinks.stream().filter(l -> l.getId().equals(id)).findFirst().ifPresent(link -> {
            taskProcessingFacade.onCreateLink(new CreateLinkInstance(link));
         });
      });

      linkTypeMapForCreatedLinks.forEach((id, linkType) -> linkTypesChanged.add(id));

      changesTracker.addLinkTypes(linkTypesChanged.stream().map(linkTypesMap::get).collect(toSet()));
      changesTracker.addUpdatedLinkInstances(updatedLinks.values().stream().flatMap(java.util.Collection::stream).collect(toSet()));
      changesTracker.updateLinkTypesMap(linkTypeMapForCreatedLinks);
      changesTracker.updateLinkTypesMap(linkTypesMap);

      linkTypesChanged.forEach(linkTypeId -> task.getDaoContextSnapshot()
                                                 .getLinkTypeDao().updateLinkType(linkTypeId, linkTypesMap.get(linkTypeId), null));

      return updatedLinks.values().stream().flatMap(java.util.Collection::stream).collect(toList());
   }

   public ChangesTracker commitOperations() {
      if (operations.isEmpty()) {
         return new ChangesTracker();
      }

      @SuppressWarnings("rawtypes") final List<Operation> invalidOperations = operations.stream().filter(operation -> !operation.isComplete()).collect(toList());
      if (invalidOperations.size() > 0) {
         final StringBuilder sb = new StringBuilder();
         invalidOperations.forEach(operation -> sb.append("Invalid update request: ").append(operation.toString()).append("\n"));
         throw new IllegalArgumentException(sb.toString());
      }

      // first create all new documents
      final List<Document> createdDocuments = createDocuments(operations.stream().filter(operation -> operation instanceof DocumentCreationOperation && operation.isComplete()).map(operation -> (DocumentCreationOperation) operation).collect(toList()));
      final Map<String, List<Document>> toBeRemovedDocumentsByCollection = Utils.categorize(operations.stream().filter(operation -> operation instanceof DocumentRemovalOperation && operation.isComplete()).map(operation -> ((DocumentRemovalOperation) operation).getEntity()), Document::getCollectionId);
      // get data structures for efficient manipulation with the new documents
      final Map<String, List<Document>> documentsByCollection = DocumentUtils.getDocumentsByCollection(createdDocuments);
      final List<String> usedCollections = new ArrayList<>(documentsByCollection.keySet());
      usedCollections.addAll(toBeRemovedDocumentsByCollection.keySet());
      final Map<String, Collection> collectionsMap = task.getDaoContextSnapshot().getCollectionDao().getCollectionsByIds(usedCollections).stream().collect(Collectors.toMap(Collection::getId, Function.identity()));
      final Map<String, String> correlationIdsToIds = createdDocuments.stream().collect(Collectors.toMap(doc -> doc.createIfAbsentMetaData().getString(Document.META_CORRELATION_ID), Document::getId));

      // report new empty documents, later updates are sent separately
      changesTracker.addCreatedDocuments(createdDocuments);
      changesTracker.addCollections(collectionsMap.values().stream().filter(c -> documentsByCollection.containsKey(c.getId())).collect(toSet()));

      // map the newly create document IDs to all other changes so that we use the correct document in updates etc.
      operations.stream().filter(operation -> operation instanceof DocumentOperation).forEach(operation -> {
         final Document doc = (Document) operation.getEntity();
         if (StringUtils.isEmpty(doc.getId()) && StringUtils.isNotEmpty(doc.createIfAbsentMetaData().getString(Document.META_CORRELATION_ID))) {
            doc.setId(correlationIdsToIds.get(doc.getMetaData().getString(Document.META_CORRELATION_ID)));
         }
      });
      operations.stream().filter(operation -> operation instanceof LinkCreationOperation).forEach(operation -> {
         final LinkInstance link = ((LinkCreationOperation) operation).getEntity();
         if (StringUtils.isEmpty(link.getId()) && StringUtils.isNotEmpty(link.getTemplateId())) {
            link.setDocumentIds(List.of(
                  correlationIdsToIds.containsKey(link.getDocumentIds().get(0)) ? correlationIdsToIds.get(link.getDocumentIds().get(0)) : link.getDocumentIds().get(0),
                  correlationIdsToIds.containsKey(link.getDocumentIds().get(1)) ? correlationIdsToIds.get(link.getDocumentIds().get(1)) : link.getDocumentIds().get(1)
            ));
         }
      });

      // commit document changes
      final List<Document> changedDocuments = commitDocumentOperations(
            operations.stream().filter(operation -> operation instanceof DocumentOperation && operation.isComplete()).map(operation -> (DocumentOperation) operation).collect(toList()),
            createdDocuments,
            collectionsMap
      );

      // remove documents
      final List<Document> removedDocuments = removeDocuments(operations.stream().filter(operation -> operation instanceof DocumentRemovalOperation).map(operation -> (DocumentRemovalOperation) operation).collect(toList()));
      changesTracker.addRemovedDocuments(removedDocuments);

      // remove created documents that were deleted later
      final Set<Document> unusedCreatedDocuments = new HashSet<>(changesTracker.getRemovedDocuments());
      unusedCreatedDocuments.retainAll(changesTracker.getCreatedDocuments());
      changesTracker.getCreatedDocuments().removeAll(unusedCreatedDocuments);

      // create new links
      final List<LinkCreationOperation> linkCreationOperations = operations.stream().filter(operation -> operation instanceof LinkCreationOperation && operation.isComplete()).map(operation -> (LinkCreationOperation) operation).collect(toList());
      final List<LinkInstance> createdLinks = createLinks(linkCreationOperations);
      final Map<String, List<LinkInstance>> linksByType = LinkTypeUtils.getLinksByType(createdLinks);
      final Map<String, LinkType> linkTypesMap = task.getDaoContextSnapshot().getLinkTypeDao().getLinkTypesByIds(linksByType.keySet()).stream().collect(Collectors.toMap(LinkType::getId, Function.identity()));
      final Map<String, String> linkCorrelationIdsToIds = createdLinks.stream().collect(Collectors.toMap(LinkInstance::getTemplateId, LinkInstance::getId));

      // report new empty links, later updates are sent separately
      changesTracker.addCreatedLinkInstances(createdLinks);
      changesTracker.addLinkTypes(linkTypesMap.values().stream().filter(c -> linksByType.containsKey(c.getId())).collect(toSet()));

      // map the newly create link IDs to all other changes so that we use the correct document in updates etc.
      operations.stream().filter(operation -> operation instanceof LinkOperation).forEach(operation -> {
         final LinkInstance link = (LinkInstance) operation.getEntity();
         if (StringUtils.isEmpty(link.getId()) && StringUtils.isNotEmpty(link.getTemplateId())) {
            link.setId(linkCorrelationIdsToIds.get(link.getTemplateId()));
         }
      });

      // commit link changes
      final List<LinkInstance> changedLinkInstances = commitLinkOperations(
            taskExecutor,
            operations.stream().filter(operation -> operation instanceof LinkOperation && operation.isComplete()).map(operation -> (LinkOperation) operation).collect(toList()),
            createdLinks,
            linkTypesMap
      );

      // report user messages, print, navigate, and send email requests for rules triggered via an Action button
      final String correlationId = task.getAppId() != null ? task.getAppId().getValue() : null;
      if (StringUtils.isNotEmpty(correlationId)) {
         final List<UserMessageRequest> userMessageRequests = operations.stream().filter(operation -> operation instanceof UserMessageOperation).map(operation -> ((UserMessageOperation) operation).getEntity()).collect(toList());
         changesTracker.addUserMessageRequests(userMessageRequests);

         List<GenericPrintRequest> printRequests = operations.stream().filter(operation -> operation instanceof PrintAttributeOperation).map(operation -> ((PrintAttributeOperation) operation).getEntity()).collect(toList());
         changesTracker.addPrintRequests(printRequests);
         printRequests = operations.stream().filter(operation -> operation instanceof PrintTextOperation).map(operation -> ((PrintTextOperation) operation).getEntity()).collect(toList());
         changesTracker.addPrintRequests(printRequests);

         final List<NavigationRequest> navigationRequests = operations.stream().filter(operation -> operation instanceof NavigationOperation).map(operation -> ((NavigationOperation) operation).getEntity()).collect(toList());
         changesTracker.addNavigationRequests(navigationRequests);

         final List<SendEmailRequest> sendEmailRequests = operations.stream().filter(operation -> operation instanceof SendEmailOperation).map(operation -> ((SendEmailOperation) operation).getEntity()).collect(toList());
         changesTracker.addSendEmailRequests(sendEmailRequests);
      }

      // propagate changes in existing documents and links that has been loaded prior to calling this rule
      task.propagateChanges(changedDocuments, changedLinkInstances);

      return changesTracker;
   }

}
