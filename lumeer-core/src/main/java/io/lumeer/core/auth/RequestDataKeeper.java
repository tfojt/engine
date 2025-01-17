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

package io.lumeer.core.auth;

import io.lumeer.api.model.AppId;
import io.lumeer.api.model.Language;

import javax.enterprise.context.RequestScoped;

@RequestScoped
public class RequestDataKeeper {

   private String correlationId;

   private AppId appId;

   private String userLocale = "en";

   private String timezone;

   public String getCorrelationId() {
      return correlationId;
   }

   public AppId getAppId() {
      return appId;
   }

   public void setCorrelationId(final String correlationId) {
      if (this.correlationId == null) {
         this.correlationId = correlationId;
      }
   }

   public void setAppId(final String appId) {
      if (this.appId == null) {
         this.appId = new AppId(appId);
      }
   }

   public String getUserLocale() {
      return userLocale;
   }

   public void setUserLocale(String userLocale) {
      this.userLocale = userLocale;
   }

   public Language getUserLanguage() {
      return Language.fromString(getUserLocale());
   }

   public String getTimezone() {
      return timezone;
   }

   public void setTimezone(final String timezone) {
      this.timezone = timezone;
   }

   public RequestDataKeeper() {
   }

   public RequestDataKeeper(final RequestDataKeeper original) {
      this.correlationId = original.getCorrelationId();
      this.userLocale = original.getUserLocale();
      this.timezone = original.getTimezone();
      this.appId = original.getAppId();
   }

   @Override
   public String toString() {
      return "RequestDataKeeper{" +
            "correlationId='" + correlationId + '\'' +
            ", appId='" + appId + '\'' +
            ", userLocale='" + userLocale + '\'' +
            ", timezone='" + timezone + '\'' +
            '}';
   }
}
