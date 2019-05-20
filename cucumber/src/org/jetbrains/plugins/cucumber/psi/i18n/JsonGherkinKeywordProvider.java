// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.plugins.cucumber.psi.i18n;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.MalformedJsonException;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.cucumber.psi.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class JsonGherkinKeywordProvider implements GherkinKeywordProvider {
  private static final Logger LOG = Logger.getInstance(JsonGherkinKeywordProvider.class.getName());

  private final GherkinKeywordList myEmptyKeywordList = new GherkinKeywordList();
  private final Map<String, GherkinKeywordList> myLanguageKeywords = new HashMap<>();
  private final Set<String> myAllStepKeywords = new HashSet<>();

  private static GherkinKeywordProvider myKeywordProvider;

  public static GherkinKeywordProvider getKeywordProvider() {
    if (myKeywordProvider == null) {
      final ClassLoader classLoader = JsonGherkinKeywordProvider.class.getClassLoader();
      if (classLoader != null) {
        final InputStream inputStream = classLoader.getResourceAsStream("i18n.json");
        if (inputStream != null) {
          myKeywordProvider = new JsonGherkinKeywordProvider(inputStream);
        }
      }

      if (myKeywordProvider == null) {
        myKeywordProvider = new PlainGherkinKeywordProvider();
      }
    }
    return myKeywordProvider;
  }

  public JsonGherkinKeywordProvider(final File keywordsFile) throws FileNotFoundException {
    this(new FileInputStream(keywordsFile));

    if (!(keywordsFile.exists() && !keywordsFile.isDirectory() && keywordsFile.canRead())){
      LOG.error("Cannot read keywords from: " + keywordsFile);
    }
  }

  public JsonGherkinKeywordProvider(final InputStream inputStream) {
    Map<String, Map<String, Object>> fromJson;
    try {
      final Reader in = new InputStreamReader(inputStream, StandardCharsets.UTF_8);
      try {
        fromJson = new Gson().fromJson(in, new TypeToken<Map<String, HashMap<String, Object>>>() {}.getType());

        for (Map.Entry<String, Map<String, Object>> entry : fromJson.entrySet()) {
          Map<String, Object> translation = entry.getValue();
          final GherkinKeywordList keywordList = new GherkinKeywordList(translation);
          myLanguageKeywords.put(entry.getKey(), keywordList);
          for (String keyword : keywordList.getAllKeywords()) {
            if (keywordList.getTokenType(keyword) == GherkinTokenTypes.STEP_KEYWORD) {
              myAllStepKeywords.add(keyword);
            }
          }
        }
      }
      finally {
        in.close();
      }
    }
    catch (MalformedJsonException e) {
      // ignore
    }
    catch (IOException e) {
      LOG.error(e);
    }
  }

  @Override
  public Collection<String> getAllKeywords(String language) {
    return getKeywordList(language).getAllKeywords();
  }

  @Override
  public IElementType getTokenType(String language, String keyword) {
    return getKeywordList(language).getTokenType(keyword);
  }

  @Override
  public String getBaseKeyword(String language, String keyword) {
    return getKeywordList(language).getBaseKeyword(keyword);
  }

  @Override
  public boolean isSpaceAfterKeyword(String language, String keyword) {
    return getKeywordList(language).isSpaceAfterKeyword(keyword);
  }

  @Override
  public boolean isStepKeyword(String keyword) {
    return myAllStepKeywords.contains(keyword);
  }

  @NotNull
  @Override
  public GherkinKeywordTable getKeywordsTable(@Nullable String language) {
    return getKeywordList(language).getKeywordsTable();
  }

  @NotNull
  private GherkinKeywordList getKeywordList(@Nullable final String language) {
    GherkinKeywordList keywordList = myLanguageKeywords.get(language);
    if (keywordList == null) {
      keywordList = myEmptyKeywordList;
    }
    return keywordList;
  }
}
