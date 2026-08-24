import UIKit

/// Minimal, dependency-free image loader for the menu icons.
///
/// The port of `IconLoader.kt`, and dependency-free for the same reason: the
/// icons are small and few, so an `NSCache` is plenty and there is no call for
/// Kingfisher any more than there was for Glide.
public actor IconLoader {

    /// Menu icons render at about 40 pt, so a 192 px longest side covers 3x.
    public static let maximumDimension: CGFloat = 192

    private let cache = NSCache<NSString, UIImage>()
    private let http: HTTPClient
    private var inFlight: [String: Task<UIImage?, Never>] = [:]

    public init(http: HTTPClient) {
        self.http = http
        // Icons are decoration; a few hundred kilobytes of them is generous.
        cache.totalCostLimit = 4 * 1024 * 1024
    }

    /// Downloads, decodes and downscales the icon at `url`, or answers nil.
    ///
    /// Never throws: a missing or unreachable icon shows the placeholder, which
    /// is what the configuration file's documentation promises.
    public func load(_ url: String) async -> UIImage? {
        if let cached = cache.object(forKey: url as NSString) { return cached }
        // A menu that lists the same icon twice should fetch it once.
        if let existing = inFlight[url] { return await existing.value }
        guard DomainRules.isHTTP(url), let target = URL(string: url) else { return nil }

        let task = Task<UIImage?, Never> { [http] in
            guard let data = try? await http.get(target, timeout: HTTPClient.iconTimeout) else {
                return nil
            }
            return IconLoader.downscale(data)
        }
        inFlight[url] = task
        let image = await task.value
        inFlight[url] = nil
        if let image {
            cache.setObject(image, forKey: url as NSString, cost: Int(image.size.width * image.size.height * 4))
        }
        return image
    }

    /// Decodes and shrinks to ``maximumDimension``, the counterpart of Android's
    /// `inSampleSize` pass.
    ///
    /// `UIImage(data:)` decodes lazily, so an oversized icon would otherwise be
    /// held at full resolution for as long as the menu is open.
    nonisolated static func downscale(_ data: Data) -> UIImage? {
        guard let image = UIImage(data: data) else { return nil }
        let longest = max(image.size.width, image.size.height)
        guard longest > maximumDimension, longest > 0 else { return image }

        let scale = maximumDimension / longest
        let size = CGSize(width: image.size.width * scale, height: image.size.height * scale)
        let format = UIGraphicsImageRendererFormat.default()
        format.opaque = false
        format.scale = 1
        return UIGraphicsImageRenderer(size: size, format: format).image { _ in
            image.draw(in: CGRect(origin: .zero, size: size))
        }
    }
}
