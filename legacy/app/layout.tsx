import type { Metadata } from "next";
import { Manrope, Newsreader } from "next/font/google";
import { headers } from "next/headers";
import "./globals.css";

const manrope = Manrope({
  variable: "--font-manrope",
  subsets: ["latin"],
});

const newsreader = Newsreader({
  variable: "--font-newsreader",
  subsets: ["latin"],
});

const title = "Mourão Clareza Contábil e Financeira | Central de comando";
const description =
  "Gestão contábil e financeira explicada para decisões mais claras, seguras e auditáveis.";

export async function generateMetadata(): Promise<Metadata> {
  const requestHeaders = await headers();
  const host =
    requestHeaders.get("x-forwarded-host") ??
    requestHeaders.get("host") ??
    "localhost:3000";
  const forwardedProtocol = requestHeaders.get("x-forwarded-proto");
  const protocol = forwardedProtocol ?? (host.includes("localhost") ? "http" : "https");
  const imageUrl = `${protocol}://${host}/og.png`;

  return {
    title,
    description,
    icons: {
      icon: "/brand/mourao-crest-navy.jpeg",
      shortcut: "/brand/mourao-crest-navy.jpeg",
    },
    openGraph: {
      title,
      description,
      type: "website",
      locale: "pt_BR",
      images: [{
        url: imageUrl,
        width: 1733,
        height: 907,
        alt: "Mourão Clareza Contábil e Financeira",
      }],
    },
    twitter: {
      card: "summary_large_image",
      title,
      description,
      images: [imageUrl],
    },
  };
}

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="pt-BR">
      <body className={`${manrope.variable} ${newsreader.variable}`}>
        {children}
      </body>
    </html>
  );
}
