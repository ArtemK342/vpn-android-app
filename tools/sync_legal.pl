#!/usr/bin/perl
# Политика и соглашение в приложении = тексты с сайта (vpn-site/privacy, vpn-site/terms).
# Править на сайте, потом запустить из корня репозитория:
#   perl tools/sync_legal.pl ../vpn-site
# Пишет {privacy,privacy_en,terms,terms_en}.html в app/src/main/assets (запасная копия, едет с релизом)
# и в vpn-site/legal/ — оттуда приложение берёт свежую версию (LoginScreen.policyRemoteUrl), так что
# для обновления текста в приложении достаточно задеплоить сайт.
use strict;
use warnings;
use utf8;
use open qw(:std :encoding(UTF-8));

my $site = shift // '../vpn-site';
my @outs = ('app/src/main/assets', "$site/legal");

my $head = <<'HTML';
<!DOCTYPE html>
<html lang="%LANG%">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0">
  <!-- Сгенерировано tools/sync_legal.pl из %SRC% на сайте — не править вручную -->
  <style>
    * { box-sizing: border-box; margin: 0; padding: 0; }
    body { background: #0d0d0d; color: #e0e0e0; font-family: monospace; font-size: 13px; line-height: 1.85; padding: 24px 20px 48px; }
    h1 { font-size: 24px; font-weight: bold; color: #ffffff; margin-bottom: 6px; }
    h2 { font-size: 14px; font-weight: bold; color: #ffffff; margin: 28px 0 10px; text-transform: uppercase; letter-spacing: 0.05em; }
    .tag { font-size: 10px; color: #39d98a; letter-spacing: 0.2em; text-transform: uppercase; margin-bottom: 10px; }
    .meta { font-size: 11px; color: #555; margin-bottom: 28px; padding-bottom: 20px; border-bottom: 1px solid #1e1e1e; }
    p { color: #888; margin-bottom: 12px; }
    ul { list-style: none; margin: 10px 0 14px; }
    li { color: #888; padding-left: 18px; position: relative; margin-bottom: 6px; }
    li::before { content: '→'; color: #39d98a; position: absolute; left: 0; }
    a { color: #39d98a; }
    .oss-badge { display: block; margin: 6px 0; word-break: break-all; }
    .highlight { background: #141414; border-left: 2px solid #39d98a; padding: 12px 16px; margin: 14px 0; color: #d0d0d0; font-size: 12px; }
    .section { margin-bottom: 28px; padding-bottom: 28px; border-bottom: 1px solid #1a1a1a; }
    .section:last-child { border-bottom: none; }
    .num { font-size: 10px; color: #39d98a; letter-spacing: 0.1em; margin-bottom: 4px; }
    .logs-on { color: #ff4444; white-space: nowrap; }
    strong { color: #e0e0e0; }
  </style>
</head>
<body>
HTML

sub slurp { my $f = shift; open my $h, '<', $f or die "$f: $!"; local $/; <$h> }

sub convert {
    my ($html, $lang, $src) = @_;
    my ($body) = $lang eq 'ru'
        ? $html =~ /id="lang-ru">(.*?)<!-- ═+ ENGLISH/s
        : $html =~ /id="lang-en">(.*?)<\/main>/s;
    die "no $lang section in $src" unless defined $body;
    $body =~ s/<\/div>\s*\z//;                                  # закрывающий lang-section
    $body =~ s/<!--.*?-->//gs;
    $body =~ s/<a href="\/" class="terms-back">.*?<\/a>//s;
    $body =~ s/<div class="terms-toc">.*?<\/ol>\s*<\/div>//s;
    $body =~ s/<div class="terms-header">\s*(.*?)\s*<\/div>\s*(?=<div class="terms-section")/$1\n/s;
    $body =~ s/<h1 class="terms-title">(.*?)<\/h1>/my $t = $1; $t =~ s|<br>| |g; "<h1>$t<\/h1>"/se;
    $body =~ s/class="terms-tag"/class="tag"/g;
    $body =~ s/class="terms-meta"/class="meta"/g;
    $body =~ s/class="terms-section-num"/class="num"/g;
    $body =~ s/class="terms-section"/class="section"/g;
    $body =~ s/class="terms-highlight"/class="highlight"/g;
    $body =~ s/href="\//href="https:\/\/fsociety-vpn.org\//g;  # в WebView нет сайта
    $body =~ s/^ {4}//mg;
    $body =~ s/\n{3,}/\n\n/g;
    (my $h = $head) =~ s/%LANG%/$lang/;
    $h =~ s/%SRC%/$src/;
    return $h . $body . "\n</body>\n</html>\n";
}

for my $doc (qw(privacy terms)) {
    my $html = slurp("$site/$doc/index.html");
    for my $lang (qw(ru en)) {
        my $page = convert($html, $lang, "$doc/index.html");
        for my $out (@outs) {
            mkdir $out unless -d $out;
            my $file = "$out/$doc" . ($lang eq 'en' ? '_en' : '') . '.html';
            open my $fh, '>', $file or die "$file: $!";
            print $fh $page;
            close $fh;
            print "$file\n";
        }
    }
}
