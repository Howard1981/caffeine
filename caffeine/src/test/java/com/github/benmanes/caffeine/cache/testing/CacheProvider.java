/*
 * Copyright 2014 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.cache.testing;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.Objects.requireNonNull;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.LogManager;
import java.util.stream.Stream;

import org.testng.annotations.DataProvider;

import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.github.benmanes.caffeine.cache.Policy;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Compute;

/**
 * A data provider that generates caches based on the {@link CacheSpec} configuration.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class CacheProvider {

  static {
    // disable logging warnings caused by exceptions in asynchronous computations
    LogManager.getLogManager().reset();
  }

  private CacheProvider() {}

  /** Returns the lazily generated test scenarios. */
  @DataProvider(name = "caches")
  public static Iterator<Object[]> providesCaches(Method testMethod) throws Exception {
    CacheGenerator generator = newCacheGenerator(testMethod);
    return asTestCases(testMethod, generator.generate());
  }

  /** Returns a new cache generator. */
  private static CacheGenerator newCacheGenerator(Method testMethod) {
    CacheSpec cacheSpec = testMethod.getAnnotation(CacheSpec.class);
    requireNonNull(cacheSpec, "@CacheSpec not found");
    Options options = Options.fromSystemProperties();

    // Inspect the test parameters for interface constraints (loading, async)
    boolean isAsyncLoadingOnly = hasCacheOfType(testMethod, AsyncLoadingCache.class);
    boolean isLoadingOnly = isAsyncLoadingOnly
        || hasCacheOfType(testMethod, LoadingCache.class)
        || options.compute().filter(Compute.ASYNC::equals).isPresent();

    return new CacheGenerator(cacheSpec, options, isLoadingOnly, isAsyncLoadingOnly);
  }

  /**
   * Converts each scenario into test case parameters. Supports injecting {@link LoadingCache},
   * {@link Cache}, {@link CacheContext}, the {@link ConcurrentMap} {@link Cache#asMap()} view,
   * {@link Policy.Eviction}, and {@link Policy.Expiration}.
   */
  private static Iterator<Object[]> asTestCases(Method testMethod,
      Stream<Entry<CacheContext, Cache<Integer, Integer>>> scenarios) {
    Parameter[] parameters = testMethod.getParameters();
    CacheContext[] stashed = new CacheContext[1];
    return scenarios.map(entry -> {
      CacheContext context = entry.getKey();
      Cache<Integer, Integer> cache = entry.getValue();

      // Retain a strong reference to the context throughout the test execution so that the
      // cache entries are not collected due to the test not accepting the context parameter
      stashed[0] = context;

      Object[] params = new Object[parameters.length];
      for (int i = 0; i < params.length; i++) {
        Class<?> clazz = parameters[i].getType();
        if (clazz.isAssignableFrom(CacheContext.class)) {
          params[i] = context;
        } else if (clazz.isAssignableFrom(Caffeine.class)) {
          params[i] = context.caffeine;
        } else if (clazz.isAssignableFrom(cache.getClass())) {
          params[i] = cache;
        } else if (clazz.isAssignableFrom(AsyncLoadingCache.class)) {
          params[i] = context.asyncCache;
        } else if (clazz.isAssignableFrom(Map.class)) {
          params[i] = cache.asMap();
        } else if (clazz.isAssignableFrom(Policy.Eviction.class)) {
          params[i] = cache.policy().eviction().get();
        } else if (clazz.isAssignableFrom(Policy.Expiration.class)) {
          params[i] = expirationPolicy(parameters[i], cache);
        }
        if (params[i] == null) {
          checkNotNull(params[i], "Unknown parameter type: %s", clazz);
        }
      }
      return params;
    }).filter(Objects::nonNull).iterator();
  }

  /** Returns the expiration policy for the given parameter. */
  private static Policy.Expiration<Integer, Integer> expirationPolicy(
      Parameter parameter, Cache<Integer, Integer> cache) {
    if (parameter.isAnnotationPresent(ExpireAfterAccess.class)) {
      return cache.policy().expireAfterAccess().get();
    } else if (parameter.isAnnotationPresent(ExpireAfterWrite.class)) {
      return cache.policy().expireAfterWrite().get();
    } else if (parameter.isAnnotationPresent(RefreshAfterWrite.class)) {
      return cache.policy().refreshAfterWrite().get();
    }
    throw new AssertionError("Expiration parameter must have a qualifier annotation");
  }

  /** Returns if the required cache matches the provided type. */
  private static boolean hasCacheOfType(Method testMethod, Class<?> cacheType) {
    return Arrays.stream(testMethod.getParameterTypes()).anyMatch(cacheType::isAssignableFrom);
  }
}
