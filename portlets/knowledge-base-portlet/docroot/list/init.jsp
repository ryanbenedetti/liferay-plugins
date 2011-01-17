<%--
/**
 * Copyright (c) 2000-2011 Liferay, Inc. All rights reserved.
 *
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 2.1 of the License, or (at your option)
 * any later version.
 *
 * This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 */
--%>

<%@ include file="/init.jsp" %>

<%
PortletPreferences preferences = renderRequest.getPreferences();

if (Validator.isNotNull(portletResource)) {
	preferences = PortletPreferencesFactoryUtil.getPortletSetup(request, portletResource);
}

Map<String, String> preferencesMap = KnowledgeBaseUtil.initPortletPreferencesMap(rootPortletId, preferences);

String articlesTitle = preferencesMap.get("articlesTitle");
boolean allArticles = GetterUtil.getBoolean(preferencesMap.get("allArticles"));
String orderByColumn = preferencesMap.get("orderByColumn");
boolean orderByAscending = GetterUtil.getBoolean(preferencesMap.get("orderByAscending"));
int articlesDelta = GetterUtil.getInteger(preferencesMap.get("articlesDelta"));
String articleWindowState = preferencesMap.get("articleWindowState");

String childArticlesDisplayStyle = preferencesMap.get("childArticlesDisplayStyle");
boolean enableArticleAssetCategories = GetterUtil.getBoolean(preferencesMap.get("enableArticleAssetCategories"));
boolean enableArticleAssetTags = GetterUtil.getBoolean(preferencesMap.get("enableArticleAssetTags"));
boolean enableArticleRatings = GetterUtil.getBoolean(preferencesMap.get("enableArticleRatings"));
boolean enableArticleComments = GetterUtil.getBoolean(preferencesMap.get("enableArticleComments"));

int rssDelta = GetterUtil.getInteger(preferencesMap.get("rssDelta"));
String rssDisplayStyle = preferencesMap.get("rssDisplayStyle");
String rssFormat = preferencesMap.get("rssFormat");

if (articleWindowState.equals(WindowState.MAXIMIZED.toString()) && windowState.equals(WindowState.MAXIMIZED)) {
	PortletURL portletURL = renderResponse.createRenderURL();

	portletURL.setWindowState(WindowState.NORMAL);

	portletDisplay.setURLBack(portletURL.toString());
}
%>