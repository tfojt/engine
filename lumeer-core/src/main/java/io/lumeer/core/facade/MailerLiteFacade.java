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
package io.lumeer.core.facade;

import io.lumeer.api.model.User;
import io.lumeer.core.facade.configuration.DefaultConfigurationProducer;

import org.apache.commons.lang3.StringUtils;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.Future;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

@ApplicationScoped
public class MailerLiteFacade implements MailerService {

   @Inject
   private DefaultConfigurationProducer defaultConfigurationProducer;

   @Inject
   private Logger log;

   final private static String ERROR_MESSAGE = "Unable to communicate with MailerLite:";

   private static String MAILERLITE_APIKEY;
   private static String MAILERLITE_GROUP_CS;
   private static String MAILERLITE_GROUP_EN;
   private static String MAILERLITE_SEQUENCE_CS;
   private static String MAILERLITE_SEQUENCE_EN;

   @PostConstruct
   public void init() {
      MAILERLITE_APIKEY = Optional.ofNullable(defaultConfigurationProducer.get(DefaultConfigurationProducer.MAILERLITE_APIKEY)).orElse("");
      MAILERLITE_GROUP_CS = Optional.ofNullable(defaultConfigurationProducer.get(DefaultConfigurationProducer.MAILERLITE_GROUP_CS)).orElse("");
      MAILERLITE_GROUP_EN = Optional.ofNullable(defaultConfigurationProducer.get(DefaultConfigurationProducer.MAILERLITE_GROUP_EN)).orElse("");
      MAILERLITE_SEQUENCE_CS = Optional.ofNullable(defaultConfigurationProducer.get(DefaultConfigurationProducer.MAILERLITE_SEQUENCE_CS)).orElse("");
      MAILERLITE_SEQUENCE_EN = Optional.ofNullable(defaultConfigurationProducer.get(DefaultConfigurationProducer.MAILERLITE_SEQUENCE_EN)).orElse("");
   }

   @Override
   public void setUserSubscription(final User user, final boolean enSite) {
      if (StringUtils.isNotEmpty(MAILERLITE_APIKEY) && user != null && user.getEmail() != null) {

         if (!userSubscribed(user.getEmail())) {
            updateUser(user.getEmail(), true);

            subscribeUser(enSite ? MAILERLITE_SEQUENCE_EN : MAILERLITE_SEQUENCE_CS, user);
            subscribeUser(enSite ? MAILERLITE_GROUP_EN : MAILERLITE_GROUP_CS, user.getName(), user.getEmail(), true);
         } else {
            updateUser(user.getEmail(), user.hasNewsletter() != null && user.hasNewsletter());
         }
      }
   }

   @Override
   public void setUserTemplate(final User user, final String template) {
      if (StringUtils.isNotEmpty(MAILERLITE_APIKEY) && user != null && StringUtils.isNotEmpty(user.getEmail())) {
         setUserTemplate(user.getEmail(), template);
      }
   }

   private static String encodeValue(String value) {
      try {
         return URLEncoder.encode(value, StandardCharsets.UTF_8.toString());
      } catch (UnsupportedEncodingException ex) {
         return value; // we tried, moreover email characters are safe anyway
      }
   }

   private boolean userSubscribed(final String userEmail) {
      String response = mailerLiteClient("subscribers/" + encodeValue(userEmail), null, false, true);

      return response.startsWith("{\"id\":") && response.contains("\"email\":\"" + userEmail + "\"");
   }

   private void subscribeUser(final String groupId, final User user) {
      subscribeUser(groupId, user.getName(), user.getEmail(), user.hasNewsletter());
   }

   private void subscribeUser(final String groupId, final String name, final String email, final boolean active) {
      mailerLiteClient("groups/" + groupId + "/subscribers", "{\"email\": \"" + email + "\", \"name\":\"" + name + "\", \"type\": \"" + (active ? "active" : "unsubscribed") +
            "\"}", false, false);
   }

   private void updateUser(final String userEmail, boolean subscribed) {
      mailerLiteClient("subscribers/" + encodeValue(userEmail), "{\"type\": \"" + (subscribed ? "active" : "unsubscribed") + "\"}", true, false);
   }

   private void setUserTemplate(final String userEmail, final String template) {
      mailerLiteClient("subscribers/" + encodeValue(userEmail), "{\"fields\": { \"template\": \"" + encodeValue(template) + "\" } }", true, false);
   }

   private String mailerLiteClient(final String path, final String body, final boolean put, final boolean blocking) {
      final Client client = ClientBuilder.newBuilder().build();
      final Invocation.Builder builder = client.target("https://api.mailerlite.com/api/v2/" + path)
                                               .request(MediaType.APPLICATION_JSON)
                                               .header("X-MailerLite-ApiKey", MAILERLITE_APIKEY)
                                               .header("Content-Type", "application/json");
      Invocation invocation;

      if (StringUtils.isNotEmpty(body)) {
         if (put) {
            invocation = builder.build("PUT", Entity.entity(body, MediaType.APPLICATION_JSON_TYPE));
         } else {
            invocation = builder.buildPost(Entity.entity(body, MediaType.APPLICATION_JSON_TYPE));
         }
      } else {
         invocation = builder.buildGet();
      }

      if (blocking) {
         return blockingCall(invocation, client);
      } else {
         asyncCall(invocation, client);
         return null;
      }
   }

   private String blockingCall(final Invocation invocation, final Client client) {
      final Response response = invocation.invoke();

      try {
         return response.readEntity(String.class);
      } catch (Exception e) {
         log.log(Level.WARNING, ERROR_MESSAGE, e);
      } finally {
         client.close();
      }

      return "";
   }

   private void asyncCall(final Invocation invocation, final Client client) {
      final Future<Response> response = invocation.submit();

      new Thread(() -> {
         try {
            final Response resp = response.get();
            if (!resp.getStatusInfo().equals(Response.Status.OK)) {
               throw new IllegalStateException("Response status is not ok: " + resp.getStatusInfo().toString());
            }
         } catch (Exception e) {
            log.log(Level.WARNING, ERROR_MESSAGE, e);
         } finally {
            client.close();
         }
      }).start();
   }

}
