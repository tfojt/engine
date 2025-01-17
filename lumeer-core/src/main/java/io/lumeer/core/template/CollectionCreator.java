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
package io.lumeer.core.template;

import io.lumeer.api.model.Attribute;
import io.lumeer.api.model.Collection;
import io.lumeer.api.model.CollectionPurpose;
import io.lumeer.api.model.CollectionPurposeType;
import io.lumeer.core.constraint.ConstraintManager;
import io.lumeer.core.facade.CollectionFacade;
import io.lumeer.core.facade.configuration.DefaultConfigurationProducer;
import io.lumeer.core.util.Utils;
import io.lumeer.engine.api.data.DataDocument;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.util.List;
import java.util.stream.Collectors;

public class CollectionCreator extends WithIdCreator {

   private final CollectionFacade collectionFacade;
   private final ConstraintManager constraintManager;
   private final ObjectMapper mapper;

   private CollectionCreator(final TemplateParser templateParser, final CollectionFacade collectionFacade, final DefaultConfigurationProducer defaultConfigurationProducer) {
      super(templateParser);
      this.collectionFacade = collectionFacade;
      this.constraintManager = ConstraintManager.getInstance(defaultConfigurationProducer);
      this.mapper = createObjectMapper();
   }

   public static void createCollections(final TemplateParser templateParser, final CollectionFacade collectionFacade, final DefaultConfigurationProducer defaultConfigurationProducer) {
      final CollectionCreator creator = new CollectionCreator(templateParser, collectionFacade, defaultConfigurationProducer);
      creator.createCollections();
   }

   private void createCollections() {
      final JSONArray collections = (JSONArray) templateParser.template.get("collections");
      var collectionsPrefix = getCollectionsPrefix(collections);

      collections.forEach(o -> {
         final String templateId = TemplateParserUtils.getId((JSONObject) o);
         final Collection collection = getCollection((JSONObject) o);
         if (!"".equals(collectionsPrefix)) {
            collection.setName(collectionsPrefix + ": " + collection.getName());
         }
         collection.setCode(null);
         final Collection storedCollection = collectionFacade.createCollection(collection, true);
         templateParser.getDict().addCollection(templateId, storedCollection);

         createAttributes(storedCollection, (JSONObject) o);
         setDefaultAttributeId(storedCollection, (JSONObject) o);
      });
   }

   @SuppressWarnings("unchecked")
   private String getCollectionsPrefix(JSONArray a) {
      var metaPrefix = StringUtils.capitalize(
            Utils.computeIfNotNull(
                  (String) ((JSONObject) templateParser.getTemplate().get("templateMeta")).get("prefix"),
                  String::toLowerCase)
      );
      var names = (List<String>) a.stream().map(o -> (String) ((JSONObject) o).get(Collection.NAME)).collect(Collectors.toList());

      if (!collectionNameExists(names)) {
         return "";
      }

      var withPrefix = names.stream().map(name -> metaPrefix + ": " + name).collect(Collectors.toList());
      if (!collectionNameExists(withPrefix)) {
         return metaPrefix;
      }

      var counter = 0;
      do {
         counter++;
         final var counterCopy = counter;
         withPrefix = names.stream().map(name -> metaPrefix + " " + counterCopy + ": " + name).collect(Collectors.toList());
      } while (collectionNameExists(withPrefix));

      return metaPrefix + " " + counter;
   }

   private boolean collectionNameExists(final List<String> names) {
      return collectionFacade.getCollections().stream().map(Collection::getName).anyMatch(names::contains);
   }

   private void setDefaultAttributeId(final Collection storedCollection, final JSONObject o) {
      final String defaultAttributeId = (String) o.get("defaultAttributeId");
      if (defaultAttributeId != null) {
         storedCollection.setDefaultAttributeId(defaultAttributeId);
      }

      collectionFacade.setDefaultAttribute(storedCollection.getId(), storedCollection.getDefaultAttributeId());
   }

   private void createAttributes(final Collection collection, final JSONObject o) {
      final List<Attribute> attributes = TemplateParserUtils.getAttributes((JSONArray) ((JSONObject) o).get("attributes"), mapper)
            .stream().map(attribute -> TemplateParserUtils.mapAttributeConstraintConfig(templateParser, attribute))
            .collect(Collectors.toList());
      collectionFacade.createCollectionAttributesWithoutPushNotification(collection.getId(), attributes);
   }

   private Collection getCollection(final JSONObject o) {
      final Collection c = new Collection(
            (String) o.get(Collection.CODE),
            (String) o.get(Collection.NAME),
            (String) o.get(Collection.ICON),
            (String) o.get(Collection.COLOR),
            null
      );

      c.setDataDescription((String) o.get(Collection.DATA_DESCRIPTION));

      final JSONObject purpose = (JSONObject) o.get(Collection.PURPOSE);

      if (purpose != null) {
         var purposeType = Utils.computeIfNotNull((String) purpose.get(CollectionPurpose.TYPE), CollectionPurposeType::valueOf);

         final JSONObject metaData = (JSONObject) purpose.get(CollectionPurpose.META_DATA);
         final DataDocument dataDocument = new DataDocument();
         if (metaData != null) {
            metaData.forEach((k, v) ->
                  dataDocument.append(
                        templateParser.translateConfig(k, constraintManager).toString(),
                        templateParser.translateConfig(v, constraintManager)
                  )
            );
         }

         c.setPurpose(new CollectionPurpose(purposeType, dataDocument));
      }

      c.setPriority((Long) o.get(Collection.PRIORITY));

      return c;
   }

}
