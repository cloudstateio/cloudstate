<!DOCTYPE html>
<html class="no-js" lang="en">

	<head>
		<title>$page.title$$if(!page.home.active)$ · $page.home.title$$endif$</title>
		<meta charset="utf-8"/>
		<meta name="viewport" content="width=device-width, initial-scale=1.0"/>
		<meta name="description" content='$page.properties.("project.description")$'/>
		$if(page.properties.("canonical.base_url"))$
		<link rel="canonical" href="$page.properties.("canonical.base_url")$$page.self.absolute.href$"/>
		$endif$
		<link href="https://fonts.googleapis.com/css?family=Source+Sans+Pro:300,300i,400,400i,700,700i&display=swap" rel="stylesheet">
		<link href="https://fonts.googleapis.com/css?family=Roboto:100normal,100italic,300normal,300italic,400normal,400italic,500normal,500italic,700normal,700italic,900normal,900italicc" rel="stylesheet" type="text/css"/>

		<!-- OneTrust Cookies Consent Notice start -->
		<script type="text/javascript">
		(function(w,d,s){if(w.location.protocol!="file:"){var f=d.getElementsByTagName(s)[0],j=d.createElement(s);
		j.async=true;j.src="https://cdn.cookielaw.org/consent/a14b53db-7715-45da-be81-7fd0cafc385d.js";
		j.charset="UTF-8";f.parentNode.insertBefore(j,f);}})(window,document,"script");</script>
		<!-- OneTrust Cookies Consent Notice end -->

		<!-- Google Tag Manager -->
		<script type="text/plain" class="optanon-category-2">(function(w,d,s,l,i){w[l]=w[l]||[];w[l].push({'gtm.start':
		new Date().getTime(),event:'gtm.js'});var f=d.getElementsByTagName(s)[0],
		j=d.createElement(s),dl=l!='dataLayer'?'&l='+l:'';j.async=true;j.src=
		'https://www.googletagmanager.com/gtm.js?id='+i+dl;f.parentNode.insertBefore(j,f);
		})(window,document,'script','dataLayer','GTM-WWL9BXX');</script>
		<!-- End Google Tag Manager -->

		<script type="text/javascript" src="$page.base$lib/jquery/jquery.min.js"></script>
		<script type="text/javascript" src="$page.base$js/page.js"></script>
		<script type="text/javascript" src="$page.base$js/warnOldVersion.js"></script>
		<script type="text/javascript" src="$page.base$js/groups.js"></script>
		<link rel="stylesheet" type="text/css" href="$page.base$lib/foundation/dist/foundation.min.css"/>
		<link rel="stylesheet" type="text/css" href="$page.base$css/page.css"/>

		<link rel="icon" type="image/x-icon" href="$page.base$../favicon.ico">
	</head>

	<body>
		<div class="off-canvas-wrapper">
			<div class="off-canvas-wrapper-inner" data-off-canvas-wrapper>

				<div class="off-canvas position-left" id="off-canvas-menu" data-off-canvas>
					<nav class="off-canvas-nav">
					$navigation()$
					</nav>
				</div>

				<div class="off-canvas-content" data-off-canvas-content>
					$oss-banner()$
					<header class="site-header expanded row">
						<div class="small-12 column">
							<a href="#" class="off-canvas-toggle hide-for-medium" data-toggle="off-canvas-menu">$menu()$</a>
							$title()$
						</div>
					</header>

				<div class="expanded row">

					<div class="medium-3 large-2 show-for-medium column site-nav-container">
						<nav class="site-nav">
							$navigation()$
						</nav>
					</div>

					<div class="small-12 medium-9 large-10 column">
						<section class="site-content">

							<span id="version-warning"></span>

							<div class="page-header row">
								<div class="medium-12 show-for-medium column">
									$if(!page.home.active)$
									<div class="nav-breadcrumbs">
										$page.breadcrumbs$
									</div>
									$endif$
								</div>
							</div>

							<div class="page-content row">
								<div class="small-12 large-9 column" id="docs">
									$page.content$
									$source()$
									$if(page.next.html)$
									<div class="nav-next">
										<p><strong>Next:</strong> $page.next.html$</p>
									</div>
									$endif$
								</div>
								<div class="large-3 show-for-large column" data-sticky-container>
									$if(page.subheaders)$
									<nav class="sidebar sticky" data-sticky data-anchor="docs" data-sticky-on="large">
										<div class="page-nav">
											<div class="nav-title">On this page:</div>
											<div class="nav-toc">
												$page.toc$
											</div>
										</div>
									</nav>
									$endif$
								</div>
							</div>

						</section>
					</div>

				</div>

				<footer class="site-footer">

					<a href="$page.base$.."><img src="$page.base$images/cloudstate-stacked-white.svg"></a>
					<p>© Cloudstate <script>document.write(new Date().getFullYear());</script> | <a href="$page.base$../privacy/">Privacy Policy</a> | <a class="optanon-toggle-display">Cookie Settings</a></p>

				</footer>

				</div>
			</div>
		</div>
	</body>

	<script type="text/javascript" src="$page.base$lib/foundation/dist/foundation.min.js"></script>
	<script type="text/javascript">jQuery(document).foundation();</script>
	<script type="text/javascript" src="$page.base$js/magellan.js"></script>

	<style type="text/css">@import "$page.base$lib/prettify/prettify.css";</style>
	<script type="text/javascript" src="$page.base$lib/prettify/prettify.js"></script>
	<script type="text/javascript" src="$page.base$lib/prettify/lang-scala.js"></script>
	<script type="text/javascript">jQuery(function(){window.prettyPrint && prettyPrint()});</script>


</html>
