// Copyright 2016 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.analysis;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.packages.ClassObjectConstructor;
import com.google.devtools.build.lib.packages.ClassObjectConstructor.Key;
import com.google.devtools.build.lib.packages.SkylarkClassObject;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * A single dependency with its configured target and aspects merged together.
 *
 * <p>This is an ephemeral object created only for the analysis of a single configured target. After
 * that configured target is analyzed, this is thrown away.
 */
public final class MergedConfiguredTarget extends AbstractConfiguredTarget {
  private final ConfiguredTarget base;
  private final TransitiveInfoProviderMap providers;

  /**
   * This exception is thrown when configured targets and aspects
   * being merged provide duplicate things that they shouldn't
   * (output groups or providers).
   */
  public static final class DuplicateException extends Exception {
    public DuplicateException(String message) {
      super(message);
    }
  }

  private MergedConfiguredTarget(ConfiguredTarget base, TransitiveInfoProviderMap providers) {
    super(base.getTarget(), base.getConfiguration());
    this.base = base;
    this.providers = providers;
  }

  @Override
  public <P extends TransitiveInfoProvider> P getProvider(Class<P> providerClass) {
    AnalysisUtils.checkProvider(providerClass);

    P provider = providers.getProvider(providerClass);
    if (provider == null) {
      provider = base.getProvider(providerClass);
    }

    return provider;
  }

  @Override
  protected void addExtraSkylarkKeys(Consumer<String> result) {
    if (base instanceof AbstractConfiguredTarget) {
      ((AbstractConfiguredTarget) base).addExtraSkylarkKeys(result);
    }
    for (int i = 0; i < providers.getProviderCount(); i++) {
      Object classAt = providers.getProviderKeyAt(i);
      if (classAt instanceof String) {
        result.accept((String) classAt);
      }
    }
  }

  @Override
  protected SkylarkClassObject rawGetSkylarkProvider(ClassObjectConstructor.Key providerKey) {
    SkylarkClassObject provider = providers.getProvider(providerKey);
    if (provider == null) {
      provider = base.get(providerKey);
    }
    return provider;
  }

  @Override
  protected Object rawGetSkylarkProvider(String providerKey) {
    Object provider = providers.getProvider(providerKey);
    if (provider == null) {
      provider = base.get(providerKey);
    }
    return provider;
  }

  /** Creates an instance based on a configured target and a set of aspects. */
  public static ConfiguredTarget of(ConfiguredTarget base, Iterable<ConfiguredAspect> aspects)
      throws DuplicateException {
    if (Iterables.isEmpty(aspects)) {
      // If there are no aspects, don't bother with creating a proxy object
      return base;
    }

    // Merge output group providers.
    OutputGroupProvider mergedOutputGroupProvider =
        OutputGroupProvider.merge(getAllOutputGroupProviders(base, aspects));

    // Merge extra-actions provider.
    ExtraActionArtifactsProvider mergedExtraActionProviders = ExtraActionArtifactsProvider.merge(
        getAllProviders(base, aspects, ExtraActionArtifactsProvider.class));

    TransitiveInfoProviderMapBuilder aspectProviders = new TransitiveInfoProviderMapBuilder();
    if (mergedOutputGroupProvider != null) {
      aspectProviders.add(mergedOutputGroupProvider);
    }
    if (mergedExtraActionProviders != null) {
      aspectProviders.add(mergedExtraActionProviders);
    }

    for (ConfiguredAspect aspect : aspects) {
      TransitiveInfoProviderMap providers = aspect.getProviders();
      for (int i = 0; i < providers.getProviderCount(); ++i) {
        Object providerKey = providers.getProviderKeyAt(i);
        if (OutputGroupProvider.class.equals(providerKey)
            || ExtraActionArtifactsProvider.class.equals(providerKey)) {
          continue;
        }

        if (providerKey instanceof Class<?>) {
          @SuppressWarnings("unchecked")
          Class<? extends TransitiveInfoProvider> providerClass =
              (Class<? extends TransitiveInfoProvider>) providerKey;
          if (base.getProvider(providerClass) != null
              || aspectProviders.contains(providerClass)) {
            throw new DuplicateException("Provider " + providerKey + " provided twice");
          }
          aspectProviders.put(
              providerClass, (TransitiveInfoProvider) providers.getProviderInstanceAt(i));
        } else if (providerKey instanceof String) {
          String legacyId = (String) providerKey;
          if (base.get(legacyId) != null || aspectProviders.contains(legacyId)) {
            throw new DuplicateException("Provider " + legacyId + " provided twice");
          }
          aspectProviders.put(legacyId, providers.getProviderInstanceAt(i));
        } else if (providerKey instanceof ClassObjectConstructor.Key) {
          ClassObjectConstructor.Key key = (Key) providerKey;
          if (base.get(key) != null || aspectProviders.contains(key)) {
            throw new DuplicateException("Provider " + key + " provided twice");
          }
          aspectProviders.put((SkylarkClassObject) providers.getProviderInstanceAt(i));
        }
      }
    }
    return new MergedConfiguredTarget(base, aspectProviders.build());
  }

  private static ImmutableList<OutputGroupProvider> getAllOutputGroupProviders(
      ConfiguredTarget base, Iterable<ConfiguredAspect> aspects) {
    OutputGroupProvider baseProvider = OutputGroupProvider.get(base);
    ImmutableList.Builder<OutputGroupProvider> providers = ImmutableList.builder();
    if (baseProvider != null) {
      providers.add(baseProvider);
    }

    for (ConfiguredAspect configuredAspect : aspects) {
      OutputGroupProvider aspectProvider = OutputGroupProvider.get(configuredAspect);;
      if (aspectProvider == null) {
        continue;
      }
      providers.add(aspectProvider);
    }
    return providers.build();
  }

  private static <T extends TransitiveInfoProvider> List<T> getAllProviders(
      ConfiguredTarget base, Iterable<ConfiguredAspect> aspects, Class<T> providerClass) {
    T baseProvider = base.getProvider(providerClass);
    List<T> providers = new ArrayList<>();
    if (baseProvider != null) {
      providers.add(baseProvider);
    }

    for (ConfiguredAspect configuredAspect : aspects) {
      T aspectProvider = configuredAspect.getProvider(providerClass);
      if (aspectProvider == null) {
        continue;
      }
      providers.add(aspectProvider);
    }
    return providers;
  }
}
