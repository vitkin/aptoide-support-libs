package cm.aptoide.com.nostra13.universalimageloader.core;

import java.io.File;

import android.content.Context;
import android.graphics.Bitmap;

import cm.aptoide.com.nostra13.universalimageloader.cache.disc.DiscCacheAware;
import cm.aptoide.com.nostra13.universalimageloader.cache.disc.impl.FileCountLimitedDiscCache;
import cm.aptoide.com.nostra13.universalimageloader.cache.disc.impl.TotalSizeLimitedDiscCache;
import cm.aptoide.com.nostra13.universalimageloader.cache.disc.impl.UnlimitedDiscCache;
import cm.aptoide.com.nostra13.universalimageloader.cache.disc.naming.FileNameGenerator;
import cm.aptoide.com.nostra13.universalimageloader.cache.disc.naming.HashCodeFileNameGenerator;
import cm.aptoide.com.nostra13.universalimageloader.cache.memory.MemoryCacheAware;
import cm.aptoide.com.nostra13.universalimageloader.cache.memory.impl.FuzzyKeyMemoryCache;
import cm.aptoide.com.nostra13.universalimageloader.cache.memory.impl.UsingFreqLimitedMemoryCache;
import cm.aptoide.com.nostra13.universalimageloader.core.assist.MemoryCacheUtil;
import cm.aptoide.com.nostra13.universalimageloader.core.display.BitmapDisplayer;
import cm.aptoide.com.nostra13.universalimageloader.core.display.SimpleBitmapDisplayer;
import cm.aptoide.com.nostra13.universalimageloader.core.download.ImageDownloader;
import cm.aptoide.com.nostra13.universalimageloader.core.download.URLConnectionImageDownloader;
import cm.aptoide.com.nostra13.universalimageloader.utils.StorageUtils;


/**
 * Factory for providing of default options for {@linkplain ImageLoaderConfiguration configuration}
 * 
 * @author Sergey Tarasevich (nostra13[at]gmail[dot]com)
 */
public class DefaultConfigurationFactory {

	/** Create {@linkplain HashCodeFileNameGenerator default implementation} of FileNameGenerator */
	public static FileNameGenerator createFileNameGenerator() {
		return new HashCodeFileNameGenerator();
	}

	/** Create default implementation of {@link DisckCacheAware} depends on incoming parameters */
	public static DiscCacheAware createDiscCache(Context context, FileNameGenerator discCacheFileNameGenerator, int discCacheSize, int discCacheFileCount) {
		if (discCacheSize > 0) {
			File individualCacheDir = StorageUtils.getIndividualCacheDirectory(context);
			return new TotalSizeLimitedDiscCache(individualCacheDir, discCacheFileNameGenerator, discCacheSize);
		} else if (discCacheFileCount > 0) {
			File individualCacheDir = StorageUtils.getIndividualCacheDirectory(context);
			return new FileCountLimitedDiscCache(individualCacheDir, discCacheFileNameGenerator, discCacheFileCount);
		} else {
			File cacheDir = StorageUtils.getCacheDirectory(context);
			return new UnlimitedDiscCache(cacheDir, discCacheFileNameGenerator);
		}
	}

	/** Create default implementation of {@link MemoryCacheAware} depends on incoming parameters */
	public static MemoryCacheAware<String, Bitmap> createMemoryCache(int memoryCacheSize, boolean denyCacheImageMultipleSizesInMemory) {
		MemoryCacheAware<String, Bitmap> memoryCache = new UsingFreqLimitedMemoryCache(memoryCacheSize);
		if (denyCacheImageMultipleSizesInMemory) {
			memoryCache = new FuzzyKeyMemoryCache<String, Bitmap>(memoryCache, MemoryCacheUtil.createFuzzyKeyComparator());
		}
		return memoryCache;
	}

	/** Create default implementation of {@link ImageDownloader} */
	public static ImageDownloader createImageDownloader() {
		return new URLConnectionImageDownloader();
	}

	/** Create default implementation of {@link BitmapDisplayer} */
	public static BitmapDisplayer createBitmapDisplayer() {
		return new SimpleBitmapDisplayer();
	}
}
