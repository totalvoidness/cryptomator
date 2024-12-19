package org.cryptomator.common.sslcontext;

import org.cryptomator.common.locationpresets.LocationPresetsProvider;
import org.cryptomator.integrations.common.CheckAvailability;
import org.cryptomator.integrations.common.IntegrationsLoader;
import org.cryptomator.integrations.common.OperatingSystem;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.ServiceLoader;
import java.util.stream.Stream;

public interface SSLContextProvider {

	Logger LOG = LoggerFactory.getLogger(SSLContextProvider.class);

	SSLContext getContext(SecureRandom csprng) throws SSLContextBuildException;

	class SSLContextBuildException extends Exception {

		SSLContextBuildException(Throwable t) {
			super(t);
		}
	}

	static Stream<SSLContextProvider> loadAll() {
		return ServiceLoader.load(SSLContextProvider.class)
				.stream()
				.filter(SSLContextProvider::isSupportedOperatingSystem)
				.filter(SSLContextProvider::passesStaticAvailabilityCheck)
				.map(ServiceLoader.Provider::get)
				.peek(impl -> logServiceIsAvailable(SSLContextProvider.class, impl.getClass()));
	}


	private static boolean isSupportedOperatingSystem(ServiceLoader.Provider<?> provider) {
		var annotations = provider.type().getAnnotationsByType(OperatingSystem.class);
		return annotations.length == 0 || Arrays.stream(annotations).anyMatch(OperatingSystem.Value::isCurrent);
	}

	private static boolean passesStaticAvailabilityCheck(ServiceLoader.Provider<?> provider) {
		return passesStaticAvailabilityCheck(provider.type());
	}

	static boolean passesStaticAvailabilityCheck(Class<?> type) {
		return passesAvailabilityCheck(type, null);
	}

	private static void logServiceIsAvailable(Class<?> apiType, Class<?> implType) {
		if (LOG.isDebugEnabled()) {
			LOG.debug("{}: Implementation is available: {}", apiType.getSimpleName(), implType.getName());
		}
	}

	private static <T> boolean passesAvailabilityCheck(Class<? extends T> type, @Nullable T instance) {
		if (!type.isAnnotationPresent(CheckAvailability.class)) {
			return true; // if type is not annotated, skip tests
		}
		if (!type.getModule().isExported(type.getPackageName(), IntegrationsLoader.class.getModule())) {
			LOG.error("Can't run @CheckAvailability tests for class {}. Make sure to export {} to {}!", type.getName(), type.getPackageName(), IntegrationsLoader.class.getPackageName());
			return false;
		}
		return Arrays.stream(type.getMethods())
				.filter(m -> isAvailabilityCheck(m, instance == null))
				.allMatch(m -> passesAvailabilityCheck(m, instance));
	}

	private static boolean passesAvailabilityCheck(Method m, @Nullable Object instance) {
		assert Boolean.TYPE.equals(m.getReturnType());
		try {
			return (boolean) m.invoke(instance);
		} catch (ReflectiveOperationException e) {
			LOG.warn("Failed to invoke @CheckAvailability test {}#{}", m.getDeclaringClass(), m.getName(), e);
			return false;
		}
	}

	private static boolean isAvailabilityCheck(Method m, boolean isStatic) {
		return m.isAnnotationPresent(CheckAvailability.class)
				&& Boolean.TYPE.equals(m.getReturnType())
				&& m.getParameterCount() == 0
				&& Modifier.isStatic(m.getModifiers()) == isStatic;
	}
}